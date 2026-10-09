package com.fullmetalsonic.dosirak.runtime

import com.fullmetalsonic.dosirak.domain.*
import com.fullmetalsonic.dosirak.site.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

interface PurchaseStorage {
    fun settings(): AppSettings
    fun overrides(): Map<LocalDate, DateOverride>
    fun records(): List<ExecutionRecord>
    fun saveSettings(settings: AppSettings)
    fun updateSettings(change: (AppSettings) -> AppSettings) { saveSettings(change(settings())) }
    fun record(record: ExecutionRecord)
    fun credentials(): Credentials?
}

/** Durable intent precedes both mutating calls; ambiguous intent permits history reads only. */
class PurchaseCoordinator(
    private val storage: PurchaseStorage,
    private val gateway: SiteGateway,
    private val clock: Clock = Clock.system(SEOUL),
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val onChanged: (ExecutionRecord) -> Unit = {}
) {
    suspend fun execute(date: LocalDate, manual: Boolean = false, acceptedPriceRisk: Boolean = false,
        expectedGeneration: Long? = null, expectedAccountGeneration: Long? = null): ExecutionRecord =
        executeRequest(date, manual, acceptedPriceRisk, expectedGeneration, expectedAccountGeneration, null)

    private data class PreparedPurchase(val plan: OrderPlan, val generation: Long, val accountGeneration: Long, val account: String)

    private suspend fun executeRequest(date: LocalDate, manual: Boolean, acceptedPriceRisk: Boolean,
        expectedGeneration: Long?, expectedAccountGeneration: Long?, prepared: PreparedPurchase?): ExecutionRecord = lock.withLock {
        val settings = storage.settings()
        val previous = storage.records().firstOrNull { it.date == date }
        val plan = ScheduleCalculator.planFor(date, settings, storage.overrides()[date])
        // A ledger survives plan/account changes and even later server cancellation.
        if (previous?.submissionPossible == true || previous?.status == ExecutionStatus.COMPLETED ||
            (previous?.status == ExecutionStatus.NEEDS_CHECK && previous.stage == "HISTORY_CONFLICT")) {
            return@withLock refreshLocked(date, previous)
        }
        val quantity = plan?.quantity ?: previous?.quantity ?: settings.defaultQuantity
        if ((expectedGeneration != null && expectedGeneration != settings.generation) ||
            (expectedAccountGeneration != null && expectedAccountGeneration != settings.accountGeneration)) {
            // A stale caller must not replace a newer execution ledger or emit a historical result alert.
            return@withLock ExecutionRecord(date, quantity, ExecutionStatus.SKIPPED, "REQUEST_STALE",
                "승인 또는 예약 알람 이후 설정·계정이 변경되어 새 신청을 건너뛰었습니다.", clock.instant(),
                accountGeneration = settings.accountGeneration, generation = settings.generation)
        }
        if (clock is ServerOrderClock) {
            val preparationProblem = preparationGuard(settings, plan, expectedGeneration, manual, acceptedPriceRisk)
            if (preparationProblem != null) return@withLock save(date, quantity, settings, ExecutionStatus.SKIPPED, "GUARD", preparationProblem)
            try {
                if (prepared == null) synchronizeTime(settings)
                else if (prepared.plan != plan || prepared.generation != settings.generation ||
                    prepared.accountGeneration != settings.accountGeneration || prepared.account != storage.credentials()?.userId) {
                    return@withLock preparationFailure(date, quantity, settings, "준비 이후 설정·계정·예약이 변경되어 새 신청을 중단했습니다.")
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                blockIntegrity(error)
                return@withLock preparationFailure(date, quantity, settings, "사이트 시각 또는 로그인 계정을 확인하지 못해 새 신청을 중단했습니다.")
            }
            if (plan != null) clock.purchaseProblem(date, plan.time)?.let {
                return@withLock preparationFailure(date, quantity, settings, it)
            }
        }
        val blocked = eligibility(date, settings, plan, manual, acceptedPriceRisk)
        if (blocked != null) return@withLock save(date, quantity, settings, ExecutionStatus.SKIPPED, "GUARD", blocked)
        val selected = requireNotNull(plan)
        val credentials = try { storage.credentials() } catch (_: Exception) { null }
        if (credentials?.userId.isNullOrBlank()) return@withLock save(date, quantity, settings, ExecutionStatus.FAILED, "ACCOUNT", "저장된 계정 식별자가 필요합니다.")
        val expectedAccount = requireNotNull(credentials).userId
        val purchaseContext = currentCoroutineContext()
        val beforeMutation: () -> Unit = {
            purchaseContext.ensureActive()
            recheck(settings, storage.settings(), selected, manual, acceptedPriceRisk, expectedAccount)?.let {
                throw SiteException("PURCHASE_GUARD", it, false)
            }
        }
        var attempt = 0
        while (true) {
            purchaseContext.ensureActive()
            val current = storage.settings()
            val changed = recheck(settings, current, selected, manual, acceptedPriceRisk, expectedAccount)
            if (changed != null) return@withLock save(date, quantity, current, ExecutionStatus.SKIPPED, "GUARD", changed)
            var menu: MenuSnapshot? = null
            try {
                menu = try { gateway.loadMenu(selected, settings.generation, settings.accountGeneration) }
                catch (error: SiteException) {
                    if (error.code != "LOGIN_REQUIRED") throw error
                    session(current, credentials)
                    gateway.loadMenu(selected, settings.generation, settings.accountGeneration)
                }
                purchaseContext.ensureActive()
                if (menu != null && (menu.date != date || menu.quantity != quantity ||
                        menu.generation != settings.generation || menu.accountGeneration != settings.accountGeneration)) {
                    throw SiteException("MENU_CONTRACT", "새 신청 화면의 날짜·수량·설정 연결을 확인하지 못했습니다.")
                }
                if (menu == null) session(current, credentials)
                val existing = classify(readVerifiedOrders(date, expectedAccount), date, quantity)
                if (existing != null) return@withLock historyResult(date, quantity, current, existing, false, null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (blockIntegrity(error)) return@withLock save(date, quantity, current, ExecutionStatus.FAILED, "SITE_BLOCKED", "인증 또는 사이트 요청 구조를 확인해야 합니다. 자동실행 의도는 유지하고 새 구매를 차단했습니다.")
                if (attempt++ < settings.retryCount) { wait(settings.retryIntervalSeconds * 1000L); continue }
                return@withLock save(date, quantity, current, ExecutionStatus.FAILED, "HISTORY", "로그인 또는 전체 주문내역을 확인하지 못해 신청하지 않았습니다.")
            }
            val beforeTemp = storage.settings()
            recheck(settings, beforeTemp, selected, manual, acceptedPriceRisk, expectedAccount)?.let {
                return@withLock save(date, quantity, beforeTemp, ExecutionStatus.SKIPPED, "GUARD", it)
            }
            save(date, quantity, beforeTemp, ExecutionStatus.RUNNING, "TEMP_INTENT", "임시 주문 전송 가능성을 기록했습니다.", possible = true)
            recheck(settings, storage.settings(), selected, manual, acceptedPriceRisk, expectedAccount)?.let {
                return@withLock save(date, quantity, beforeTemp, ExecutionStatus.NEEDS_CHECK, "TEMP_BLOCKED", it, true)
            }
            val checkout = try { gateway.createCheckout(selected, menu, beforeMutation) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val blockedIntegrity = blockIntegrity(error)
                val possible = error !is SiteException || error.submissionPossible
                if (possible) return@withLock save(date, quantity, beforeTemp, ExecutionStatus.NEEDS_CHECK, "TEMP_UNKNOWN", "임시 주문 결과가 불명확하여 다시 제출하지 않습니다.", possible = true)
                save(date, quantity, beforeTemp, ExecutionStatus.FAILED, "PRE_TEMP", "임시 주문 전 단계에서 중단했습니다.")
                if (!blockedIntegrity && attempt++ < settings.retryCount) { wait(settings.retryIntervalSeconds * 1000L); continue }
                return@withLock storage.records().first { it.date == date }
            }
            if (checkout.date != date || checkout.quantity != quantity || checkout.accountId != expectedAccount ||
                checkout.unitPrice <= 0 || checkout.unitPrice > Long.MAX_VALUE / quantity ||
                checkout.total != checkout.unitPrice * quantity || !ReservationLimits.amountAllowed(beforeTemp, quantity, checkout.total)) {
                if (checkout.date != date || checkout.quantity != quantity || checkout.accountId != expectedAccount ||
                    checkout.unitPrice <= 0 || checkout.unitPrice > Long.MAX_VALUE / quantity || checkout.total != checkout.unitPrice * quantity) {
                    blockNewPurchases("신청정보의 계정·날짜·수량·금액 연결을 확인하기 전 새 구매를 차단합니다.")
                }
                return@withLock save(date, quantity, beforeTemp, ExecutionStatus.NEEDS_CHECK, "CHECKOUT_BLOCKED", "신청정보의 계정·날짜·수량·금액 또는 허용액이 일치하지 않아 최종 제출을 중단했습니다.", possible = true, amount = checkout.total)
            }
            try {
                val intervening = classify(readVerifiedOrders(date, expectedAccount), date, quantity)
                if (intervening != null) return@withLock historyResult(date, quantity, beforeTemp, intervening, true, checkout.total)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                blockIntegrity(error)
                return@withLock save(date, quantity, beforeTemp, ExecutionStatus.NEEDS_CHECK, "PRE_SUBMIT_HISTORY", "최종 제출 직전 주문내역을 확인하지 못해 제출을 중단했습니다.", true, checkout.total)
            }
            val beforeSubmit = storage.settings()
            recheck(settings, beforeSubmit, selected, manual, acceptedPriceRisk, expectedAccount)?.let {
                return@withLock save(date, quantity, beforeTemp, ExecutionStatus.NEEDS_CHECK, "SUBMIT_BLOCKED", it, possible = true, amount = checkout.total)
            }
            if (!ReservationLimits.amountAllowed(beforeSubmit, quantity, checkout.total)) {
                return@withLock save(date, quantity, beforeSubmit, ExecutionStatus.NEEDS_CHECK, "LIMIT_CHANGED", "현재 허용액을 넘어서 최종 제출을 중단했습니다.", true, checkout.total)
            }
            save(date, quantity, beforeSubmit, ExecutionStatus.RUNNING, "SUBMIT_INTENT", "최종 주문 전송 가능성을 기록했습니다.", possible = true, amount = checkout.total)
            recheck(settings, storage.settings(), selected, manual, acceptedPriceRisk, expectedAccount)?.let {
                return@withLock save(date, quantity, beforeSubmit, ExecutionStatus.NEEDS_CHECK, "SUBMIT_BLOCKED", it, true, checkout.total)
            }
            var mayHaveSubmitted = true
            try { gateway.submit(checkout, beforeMutation) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { blockIntegrity(error); mayHaveSubmitted = error !is SiteException || error.submissionPossible }
            var result: HistoryState? = null
            for (read in 0..2) {
                if (read > 0) wait(3000L)
                try { result = classify(readVerifiedOrders(date, expectedAccount), date, quantity) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { blockIntegrity(error); result = null }
                if (result != null) break
            }
            if (result == null) return@withLock save(date, quantity, beforeSubmit, ExecutionStatus.NEEDS_CHECK, "SUBMIT_UNKNOWN", "최종 요청 후 주문완료를 확인하지 못했습니다. 빈 내역도 미접수 증거가 아니므로 조회만 가능합니다.", possible = true, amount = checkout.total)
            val verified = historyResult(date, quantity, beforeSubmit, result, true, checkout.total)
            if (verified.status == ExecutionStatus.COMPLETED && mayHaveSubmitted) {
                storage.updateSettings { current ->
                    if (current.accountGeneration == settings.accountGeneration && current.generation == settings.generation) {
                        current.copy(verifiedLiveAccountGeneration = settings.accountGeneration)
                    } else current
                }
            }
            return@withLock verified
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    suspend fun prepareAndExecute(date: LocalDate, expectedGeneration: Long): ExecutionRecord {
        var prepared: PreparedPurchase? = null
        val stopped = lock.withLock {
            val settings = storage.settings()
            val previous = storage.records().firstOrNull { it.date == date }
            if (previous?.submissionPossible == true || previous?.status == ExecutionStatus.COMPLETED ||
                (previous?.status == ExecutionStatus.NEEDS_CHECK && previous.stage == "HISTORY_CONFLICT")) {
                return@withLock refreshLocked(date, previous)
            }
            val plan = ScheduleCalculator.planFor(date, settings, storage.overrides()[date])
            val quantity = plan?.quantity ?: settings.defaultQuantity
            if (expectedGeneration != settings.generation) return@withLock ExecutionRecord(date, quantity,
                ExecutionStatus.SKIPPED, "REQUEST_STALE", "예약 이후 설정이 변경되어 새 신청을 중단했습니다.", clock.instant(),
                accountGeneration = settings.accountGeneration, generation = settings.generation)
            val guard = preparationGuard(settings, plan, expectedGeneration, false, false)
            if (guard != null) return@withLock preparationFailure(date, quantity, settings, guard)
            val serverClock = clock as? ServerOrderClock
                ?: return@withLock preparationFailure(date, quantity, settings, "사이트 시각 대기를 사용할 수 없어 새 신청을 중단했습니다.")
            val started = serverClock.elapsedNow()
            val selected = requireNotNull(plan)
            val account = try { storage.credentials()?.userId } catch (_: Exception) { null }
            if (account.isNullOrBlank()) return@withLock preparationFailure(date, quantity, settings, "저장된 계정 식별자가 필요합니다.")
            try {
                synchronizeTime(settings)
                val existing = classify(readVerifiedOrders(date, account), date, quantity)
                if (storage.settings().accountGeneration != settings.accountGeneration || storage.credentials()?.userId != account) {
                    return@withLock preparationFailure(date, quantity, settings, "사전 조회 중 계정이 변경되어 준비를 중단했습니다.")
                }
                if (existing != null) return@withLock historyResult(date, quantity, settings, existing, false, null)
                var resynced = false
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val current = storage.settings()
                    val changed = preparationGuard(current, ScheduleCalculator.planFor(date, current, storage.overrides()[date]), expectedGeneration, false, false)
                    if (changed != null || current.accountGeneration != settings.accountGeneration ||
                        ScheduleCalculator.planFor(date, current, storage.overrides()[date]) != selected || storage.credentials()?.userId != account) {
                        return@withLock preparationFailure(date, quantity, settings, changed ?: "준비 중 설정·계정·예약이 변경되어 새 신청을 중단했습니다.")
                    }
                    val elapsed = serverClock.elapsedNow() - started
                    if (elapsed !in 0 until 180_000) return@withLock preparationFailure(date, quantity, settings, "사이트 시각 대기 제한을 넘어 새 신청을 중단했습니다.")
                    val remaining = serverClock.delayUntil(date, maxOf(selected.time, LocalTime.of(6, 0)))
                        ?: return@withLock preparationFailure(date, quantity, settings, "사이트 시각 확인값이 만료되어 새 신청을 중단했습니다.")
                    val bounds = serverClock.bounds() ?: return@withLock preparationFailure(date, quantity, settings, "사이트 시각을 확인할 수 없습니다.")
                    val latest = java.time.Instant.ofEpochMilli(bounds.latestEpochMillis).atZone(SEOUL)
                    if (latest.toLocalDate() != date || latest.toLocalTime() >= LocalTime.of(8, 0)) {
                        return@withLock preparationFailure(date, quantity, settings, "사이트 기준 날짜 또는 마감 시각을 벗어나 새 신청을 중단했습니다.")
                    }
                    if (!resynced && remaining <= 15_000) {
                        refreshTimeOnly(serverClock)
                        resynced = true
                        continue
                    }
                    if (remaining == 0L) {
                        serverClock.purchaseProblem(date, selected.time)?.let {
                            return@withLock preparationFailure(date, quantity, settings, it)
                        }
                        prepared = PreparedPurchase(selected, settings.generation, settings.accountGeneration, account)
                        return@withLock null
                    }
                    wait(minOf(500L, remaining))
                }
                @Suppress("UNREACHABLE_CODE") null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                blockIntegrity(error)
                preparationFailure(date, quantity, settings, "사이트 시각 또는 로그인 계정을 확인하지 못해 새 신청을 중단했습니다.")
            }
        }
        return stopped ?: executeRequest(date, false, false, expectedGeneration, prepared?.accountGeneration, requireNotNull(prepared))
    }

    private fun preparationGuard(settings: AppSettings, plan: OrderPlan?, expectedGeneration: Long?, manual: Boolean, risk: Boolean): String? = when {
        expectedGeneration != null && expectedGeneration != settings.generation -> "승인 또는 예약 이후 설정이 변경되어 새 신청을 중단했습니다."
        plan == null -> "실행할 예약 계획이 없거나 신청 제외 날짜입니다."
        plan.time >= LocalTime.of(8, 0) -> "08:00 이후의 예약은 실행하지 않습니다."
        settings.liveBlockedReason != null -> settings.liveBlockedReason
        ReservationLimits.validate(settings) != null -> ReservationLimits.validate(settings)
        manual && !risk -> "이번 실제 구매의 가격 변동 위험 동의가 필요합니다."
        !manual && !settings.masterEnabled -> "예약 자동실행이 꺼져 있습니다."
        !manual && !settings.displayPriceRiskAccepted -> "표시금액과 실제 공제액 변동 위험 동의가 필요합니다."
        !manual && settings.liveScope == LiveScope.NONE -> "실제 구매 실행 범위가 설정되지 않았습니다."
        !manual && settings.liveScope == LiveScope.SINGLE_DATE && settings.liveTestDate != plan.date -> "동의한 단일 구매 날짜가 아닙니다."
        else -> null
    }

    private suspend fun synchronizeTime(settings: AppSettings) {
        currentCoroutineContext().ensureActive()
        val credentials = storage.credentials()
        if (credentials?.userId.isNullOrBlank()) throw SiteException("ACCOUNT_REQUIRED", "저장된 계정 식별자가 필요합니다.")
        session(settings, credentials)
        gateway.verifyAccount(requireNotNull(credentials).userId)
        currentCoroutineContext().ensureActive()
        val sample = gateway.probeServerTime()
        if (!(clock as ServerOrderClock).accept(sample)) throw SiteException("TIME_UNAVAILABLE", "사이트 시각 확인값이 유효하지 않습니다.")
        currentCoroutineContext().ensureActive()
    }

    private suspend fun refreshTimeOnly(serverClock: ServerOrderClock) {
        currentCoroutineContext().ensureActive()
        val sample = try { gateway.probeServerTime() }
        catch (error: SiteException) {
            currentCoroutineContext().ensureActive()
            if (error.code == "NETWORK" && serverClock.bounds() != null) return
            throw error
        }
        currentCoroutineContext().ensureActive()
        if (!serverClock.accept(sample)) throw SiteException("TIME_UNAVAILABLE", "직전 사이트 시각 확인값이 유효하지 않습니다.")
    }

    private fun preparationFailure(date: LocalDate, quantity: Int, settings: AppSettings, message: String): ExecutionRecord {
        val record = ExecutionRecord(date, quantity, ExecutionStatus.FAILED, "PREPARATION", message, clock.instant(),
            accountGeneration = settings.accountGeneration, generation = settings.generation)
        runCatching { onChanged(record) }
        return record
    }

    suspend fun refreshOrders(date: LocalDate): ExecutionRecord = lock.withLock {
        refreshLocked(date, storage.records().firstOrNull { it.date == date })
    }

    private fun refreshLocked(date: LocalDate, previous: ExecutionRecord?): ExecutionRecord {
        val settings = storage.settings()
        val protectedRecord = previous?.takeIf { !it.isNonSubmissionObservation() &&
            (it.submissionPossible || it.status == ExecutionStatus.COMPLETED || it.status == ExecutionStatus.NEEDS_CHECK ||
                it.serverOrderId != null || it.amount != null) }
        val quantity = protectedRecord?.quantity ?: ScheduleCalculator.planFor(date, settings, storage.overrides()[date])?.quantity ?: settings.defaultQuantity
        if (previous != null && previous.accountGeneration != settings.accountGeneration && (previous.submissionPossible || previous.status == ExecutionStatus.COMPLETED)) {
            return persist(previous.copy(status = ExecutionStatus.NEEDS_CHECK, stage = "ACCOUNT_CHANGED", message = "이전 계정의 전송 기록입니다. 새 계정에서 재신청하지 않습니다.", updatedAt = clock.instant(), submissionPossible = true))
        }
        try {
            val credentials = storage.credentials()
            if (credentials?.userId.isNullOrBlank()) throw SiteException("ACCOUNT_REQUIRED", "저장된 계정 식별자가 필요합니다.")
            session(settings, credentials)
            val state = classify(readVerifiedOrders(date, requireNotNull(credentials).userId), date, quantity)
            val latest = storage.settings()
            if (latest.accountGeneration != settings.accountGeneration || storage.credentials()?.userId != credentials?.userId) {
                return lookupObservation(date, quantity, settings, protectedRecord, false, "조회 중 계정이 변경되어 주문 결과를 연결하지 않았습니다.")
            }
            if (state != null) return historyResult(date, quantity, settings, state, protectedRecord?.submissionPossible == true || protectedRecord?.status == ExecutionStatus.COMPLETED, protectedRecord?.amount)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            blockIntegrity(error)
            return lookupObservation(date, quantity, settings, protectedRecord, false, "주문내역을 불러오지 못했습니다. 다시 확인해 주세요.")
        }
        return lookupObservation(date, quantity, settings, protectedRecord, true,
            if (protectedRecord != null) "일치하는 활성 주문이 없습니다. 이전 주문 기록은 유지하며 자동 재신청하지 않습니다."
            else "주문내역에 신청된 도시락이 없습니다.")
    }

    private fun lookupObservation(date: LocalDate, quantity: Int, settings: AppSettings, protectedRecord: ExecutionRecord?, empty: Boolean,
        message: String): ExecutionRecord {
        val stage = if (empty) "LOOKUP_EMPTY" else "LOOKUP_ERROR"
        if (protectedRecord != null) return protectedRecord.copy(status = ExecutionStatus.NEEDS_CHECK, stage = stage,
            message = message, updatedAt = clock.instant(), submissionPossible = protectedRecord.submissionPossible || protectedRecord.status == ExecutionStatus.COMPLETED)
        return ExecutionRecord(date, quantity, if (empty) ExecutionStatus.SKIPPED else ExecutionStatus.FAILED,
            stage, message, clock.instant(), accountGeneration = settings.accountGeneration, generation = settings.generation)
    }

    private fun session(settings: AppSettings, credentials: Credentials?) {
        try { gateway.ensureSession(null) }
        catch (error: SiteException) {
            if (error.code != "LOGIN_REQUIRED" || !settings.useStoredCredentials || credentials == null) throw error
            gateway.ensureSession(credentials)
        }
    }

    private fun readVerifiedOrders(date: LocalDate, expectedAccount: String): List<SiteOrder> {
        gateway.verifyAccount(expectedAccount)
        return gateway.readOrders(date)
    }

    private fun blockIntegrity(error: Exception): Boolean {
        val code = (error as? SiteException)?.code ?: return false
        val blocked = code.endsWith("_CONTRACT") || code.endsWith("_CONFLICT") || code in setOf(
            "LOGIN_REQUIRED", "LOGIN_FAILED", "ADDITIONAL_AUTH", "ACCOUNT_MISMATCH", "ACCOUNT_UNVERIFIED", "PRICE_MISSING", "PRICE_INVALID", "PRICE_MISMATCH", "REDIRECT_BLOCKED"
        )
        if (blocked) blockNewPurchases("$code: 인증 또는 사이트 주문 구조가 변경되었습니다. 확인하기 전 새 구매를 차단합니다.")
        return blocked
    }

    private fun blockNewPurchases(reason: String) {
        storage.updateSettings { current -> current.copy(liveBlockedReason = reason) }
    }

    private fun eligibility(date: LocalDate, settings: AppSettings, plan: OrderPlan?, manual: Boolean, risk: Boolean): String? {
        val timeProblem = if (clock is ServerOrderClock && plan != null) clock.purchaseProblem(date, plan.time) else null
        val now = clock.instant().atZone(SEOUL)
        return when {
            timeProblem != null -> timeProblem
            date != now.toLocalDate() -> "한국시간 당일만 새로 신청할 수 있습니다."
            plan == null -> "실행할 예약 계획이 없거나 신청 제외 날짜입니다."
            now.toLocalTime() < maxOf(plan.time, LocalTime.of(6, 0)) -> "저장된 신청 시각과 06:00 이후에만 신청할 수 있습니다."
            now.toLocalTime() >= LocalTime.of(8, 0) -> "08:00 마감 이후에는 새로 신청하지 않습니다."
            settings.liveBlockedReason != null -> settings.liveBlockedReason
            ReservationLimits.validate(settings) != null -> ReservationLimits.validate(settings)
            manual && !risk -> "이번 실제 구매의 가격 변동 위험 동의가 필요합니다."
            !manual && !settings.masterEnabled -> "예약 자동실행이 꺼져 있습니다."
            !manual && !settings.displayPriceRiskAccepted -> "표시금액과 실제 공제액 변동 위험 동의가 필요합니다."
            !manual && settings.liveScope == LiveScope.NONE -> "실제 구매 실행 범위가 설정되지 않았습니다."
            !manual && settings.liveScope == LiveScope.SINGLE_DATE && settings.liveTestDate != date -> "동의한 단일 구매 날짜가 아닙니다."
            else -> null
        }
    }

    private fun recheck(initial: AppSettings, current: AppSettings, plan: OrderPlan, manual: Boolean, risk: Boolean, account: String): String? {
        if (current.generation != initial.generation || current.accountGeneration != initial.accountGeneration) return "설정 또는 계정이 변경되어 제출을 중단했습니다."
        if (ScheduleCalculator.planFor(plan.date, current, storage.overrides()[plan.date]) != plan) return "예약 날짜·수량·시각이 변경되어 제출을 중단했습니다."
        val currentAccount = try { storage.credentials()?.userId } catch (_: Exception) { null }
        if (currentAccount != account) return "저장 계정이 변경되어 제출을 중단했습니다."
        return eligibility(plan.date, current, plan, manual, risk)
    }

    private data class HistoryState(val order: SiteOrder?, val conflict: Boolean)

    private fun classify(orders: List<SiteOrder>, date: LocalDate, quantity: Int): HistoryState? {
        val active = orders.filter { it.date == date && it.status.trim() !in setOf("주문취소", "취소완료") }
        if (active.isEmpty()) return null
        val one = active.singleOrNull()
        return if (one != null && one.status.trim() == "주문완료" && one.quantity == quantity && (one.total ?: 0) > 0) HistoryState(one, false)
        else HistoryState(null, true)
    }

    private fun historyResult(date: LocalDate, quantity: Int, settings: AppSettings, state: HistoryState, possible: Boolean, expectedAmount: Long?): ExecutionRecord {
        if (state.conflict) return save(date, quantity, settings, ExecutionStatus.NEEDS_CHECK, "HISTORY_CONFLICT", "당일 활성 주문의 수량·금액·상태 또는 여러 주문이 충돌하여 추가 제출하지 않습니다.", possible, expectedAmount)
        val order = requireNotNull(state.order)
        if (expectedAmount != null && order.total != expectedAmount) {
            blockNewPurchases("표시금액과 실제 주문금액이 다릅니다. 금액을 확인하기 전 새 구매를 차단합니다.")
            return save(date, quantity, settings, ExecutionStatus.NEEDS_CHECK, "AMOUNT_MISMATCH", "주문 내역 금액이 제출 전 표시금액과 다릅니다. 자동취소하지 않고 이후 구매를 차단했습니다.", true, order.total, order.id)
        }
        return save(date, quantity, settings, ExecutionStatus.COMPLETED, "VERIFIED", "같은 내역 행의 날짜·수량·주문완료·양수 금액을 확인했습니다.", possible, order.total, order.id)
    }

    private fun save(date: LocalDate, quantity: Int, settings: AppSettings, status: ExecutionStatus, stage: String, message: String,
        possible: Boolean = false, amount: Long? = null, serverId: String? = null): ExecutionRecord = persist(
        ExecutionRecord(date, quantity, status, stage, message, clock.instant(), amount, serverId, possible, settings.accountGeneration, settings.generation)
    )

    private fun persist(record: ExecutionRecord): ExecutionRecord {
        storage.record(record)
        runCatching { onChanged(record) }
        return record
    }

    companion object {
        private val SEOUL = ZoneId.of("Asia/Seoul")
        private val lock = Mutex()
    }
}
