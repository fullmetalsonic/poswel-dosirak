package com.fullmetalsonic.dosirak

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import com.fullmetalsonic.dosirak.domain.*
import com.fullmetalsonic.dosirak.platform.ReservationScheduler
import com.fullmetalsonic.dosirak.runtime.AppRuntime
import com.fullmetalsonic.dosirak.site.SiteException
import com.fullmetalsonic.dosirak.ui.UiAction
import com.fullmetalsonic.dosirak.ui.UiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

class AppController(
    context: Context,
    private val runtime: AppRuntime,
    private val requestExport: () -> Unit,
    private val requestImport: () -> Unit
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engineMutex = Mutex()
    private val mutableState = MutableStateFlow(UiState(busy = true))
    val state = mutableState.asStateFlow()
    private var working = false
    private var priorityStops = 0
    private val stopSequence = AtomicLong()
    private var loaded = false
    @Volatile private var verifiedSession: VerifiedSession? = null

    init {
        scope.launch { runtime.updates.collect { reload() } }
        scope.launch { runtime.engine.active.collect { active ->
            mutableState.update { it.copy(busy = working || priorityStops > 0 || !loaded || active) }
        } }
    }

    fun onAction(action: UiAction) {
        when (action) {
            UiAction.ClearMessage -> mutableState.update { it.copy(message = null) }
            is UiAction.OpenSite -> mutableState.update {
                it.copy(sitePath = safeSitePath(action.path), siteRequest = it.siteRequest + 1)
            }
            is UiAction.OpenEnvironment -> {
                if (!runtime.environment.open(action.key)) message("Android 설정 화면을 열지 못했습니다.")
            }
            UiAction.ExportBackup -> requestExport()
            UiAction.ImportBackup -> if (state.value.busy) message("진행 중인 작업이 끝난 뒤 백업을 가져오세요.") else requestImport()
            UiAction.RefreshEnvironment -> refreshEnvironment()
            UiAction.TestSound -> runtime.notifier.testSound()
            is UiAction.SaveSettings -> {
                val masterOff = state.value.settings.masterEnabled && !action.settings.masterEnabled
                val dayOff = state.value.settings.dayAutoEnabled && !action.settings.dayAutoEnabled
                if (isBusy() && (masterOff || dayOff)) priorityStop {
                    val latest = runtime.store.loadSettings()
                    runtime.store.saveSettings(latest.copy(
                        masterEnabled = if (masterOff) false else latest.masterEnabled,
                        dayAutoEnabled = if (dayOff) false else latest.dayAutoEnabled,
                        generation = latest.generation + 1))
                } else work { sequence -> saveSettings(action.settings, sequence) }
            }
            is UiAction.SaveDate -> {
                if (isBusy() && action.value.policy == DatePolicy.EXCLUDE) priorityStop {
                    val latest = runtime.store.loadOverrides()[action.value.date] ?: action.value
                    runtime.store.saveOverride(latest.copy(policy = DatePolicy.EXCLUDE))
                    advanceGeneration()
                } else work { sequence ->
                    planWrite(sequence) {
                        requireUser(action.value.quantity == null || action.value.quantity in 1..5, "수량은 1~5개로 입력하세요.")
                        runtime.store.saveOverride(action.value)
                        advanceGeneration()
                    }
                    changedAndReschedule()
                    message("날짜 설정을 저장했습니다.")
                }
            }
            is UiAction.RestoreDate -> work { sequence ->
                planWrite(sequence) {
                    runtime.store.deleteOverride(action.date)
                    advanceGeneration()
                }
                changedAndReschedule()
                message("이 날짜를 자동 설정으로 복원했습니다.")
            }
            is UiAction.SaveCredentials -> work { saveCredentials(action.userId, action.password) }
            UiAction.DeleteCredentials -> work {
                stopForAccountChange()
                runtime.vault.clear()
                clearCookies()
                changedAndReschedule()
                message("저장 계정을 삭제하고 실제구매 예약을 해제했습니다.")
            }
            UiAction.CheckLogin -> work {
                verifiedSession = null
                val settings = runtime.store.loadSettings()
                try { runtime.gateway.ensureSession(null) }
                catch (failure: SiteException) {
                    if (failure.code != "LOGIN_REQUIRED" || !settings.useStoredCredentials) throw failure
                    val credentials = runtime.vault.load() ?: throw UserFailure("사이트 로그인 또는 저장 계정이 필요합니다.")
                    runtime.gateway.ensureSession(credentials)
                }
                val expected = runtime.vault.load()?.userId
                    ?: throw UserFailure("로그인 계정을 대조하려면 아이디를 먼저 저장하세요.")
                runtime.gateway.verifyAccount(expected)
                val (reason, loginOnlyBlock) = synchronized(runtime.store) {
                    val latest = runtime.store.loadSettings()
                    requireUser(latest.accountGeneration == settings.accountGeneration,
                        "확인 중 저장 계정이 변경되었습니다. 다시 로그인 확인을 실행하세요.")
                    verifiedSession = VerifiedSession(expected, latest.accountGeneration)
                    val reason = latest.liveBlockedReason
                    val loginOnlyBlock = reason != null && listOf("LOGIN_REQUIRED:", "LOGIN_FAILED:", "ACCOUNT_MISMATCH:")
                        .any { reason.startsWith(it) }
                    if (loginOnlyBlock) {
                        runtime.store.saveSettings(latest.copy(liveBlockedReason = null, generation = latest.generation + 1))
                    }
                    reason to loginOnlyBlock
                }
                if (loginOnlyBlock) changedAndReschedule()
                message("저장 계정 ${maskId(expected)}과 사이트 로그인 계정이 일치합니다." +
                    if (reason != null && !loginOnlyBlock) " 기존 실제구매 차단은 유지됩니다. 차단 원인을 별도로 확인하세요." else "")
            }
            is UiAction.ReviewBlock -> {
                val acknowledgedReason = state.value.settings.liveBlockedReason
                work { reviewBlock(action.approved, acknowledgedReason) }
            }
            is UiAction.MockOrder -> work { mockOrder(action.date) }
            is UiAction.OrderNow -> work {
                requireUser(action.acceptedPriceRisk, "실제 구매와 가격 변동 위험에 먼저 동의하세요.")
                val result = engineMutex.withLock {
                    runtime.engine.execute(action.date, manual = true, acceptedPriceRisk = true)
                }
                runtime.scheduler.reschedule()
                message(result.message)
            }
            is UiAction.RefreshOrders -> work {
                engineMutex.withLock { runtime.engine.refreshOrders(action.date) }
                runtime.scheduler.reschedule()
                message("주문내역 조회 결과를 갱신했습니다.")
            }
            is UiAction.ArmLive -> work { sequence -> armLive(action, sequence) }
        }
    }

    fun exportTo(uri: Uri) {
        // Export only plans; AppStore strips credentials, history and live consent.
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val backup = runtime.store.exportPlanBackup()
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw UserFailure("백업 파일을 열지 못했습니다.")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(backup) }
                }
                message("설정 백업을 내보냈습니다. 계정과 실제구매 승인은 포함되지 않습니다.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message("백업을 내보내지 못했습니다. 저장 위치를 확인하세요.") }
        }
    }

    fun importFrom(uri: Uri) = work { sequence ->
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                requireUser(bytes.size() + count <= 1_000_000, "백업 파일이 너무 큽니다.")
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray().toString(Charsets.UTF_8)
        } ?: throw UserFailure("백업 파일을 열지 못했습니다.")
        planWrite(sequence) { runtime.store.importPlanBackup(text) }
        changedAndReschedule()
        message("백업을 가져왔습니다. 근무 기준 확인과 실제구매 동의를 다시 설정하세요.")
    }

    fun refreshEnvironment() {
        scope.launch {
            try {
                val environment = withContext(Dispatchers.IO) {
                    runtime.scheduler.reschedule()
                    runtime.environment.inspect()
                }
                mutableState.update { it.copy(environment = environment,
                    lastEnvironmentCheck = ZonedDateTime.now(ReservationScheduler.ZONE)
                        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))) }
                if (!loaded) reload()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message("실행환경을 확인하지 못했습니다.") }
        }
    }

    fun close() { scope.cancel() }

    private fun isBusy() = state.value.busy || working || priorityStops > 0 || runtime.engine.active.value

    private fun priorityStop(change: () -> Unit) {
        stopSequence.incrementAndGet()
        priorityStops++
        mutableState.update { it.copy(busy = true) }
        scope.launch {
            try {
                withContext(Dispatchers.IO + NonCancellable) {
                    synchronized(runtime.store) { change() }
                    changedAndReschedule()
                }
                message("중지 설정을 저장했습니다. 아직 전송되지 않은 신청은 다음 확인 단계에서 중단합니다. 이미 전송된 주문은 취소되지 않습니다.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message("중지 설정을 저장하지 못했습니다. 주문내역과 실행 상태를 확인하세요.") }
            finally {
                priorityStops--
                reload()
            }
        }
    }

    private fun planWrite(sequence: Long, change: () -> Unit) = synchronized(runtime.store) {
        requireUser(sequence == stopSequence.get(), "중지 설정이 먼저 적용되어 이전 편집 저장을 중단했습니다.")
        change()
    }

    private fun work(block: suspend (Long) -> Unit) {
        if (isBusy()) {
            message("진행 중인 작업이 끝난 뒤 다시 시도하세요.")
            return
        }
        working = true
        val sequence = stopSequence.get()
        mutableState.update { it.copy(busy = true) }
        scope.launch {
            try { withContext(Dispatchers.IO) { block(sequence) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: UserFailure) { message(failure.message ?: "입력값을 확인하세요.") }
            catch (_: Exception) { message("작업을 완료하지 못했습니다. 로그인·입력값·인터넷 연결을 확인하세요.") }
            finally {
                working = false
                reload()
            }
        }
    }

    private suspend fun reload() {
        try {
            val snapshot = withContext(Dispatchers.IO) {
                val settings = runtime.store.loadSettings()
                val credentials = runCatching { runtime.vault.load() }.getOrNull()
                UiState(settings = settings, overrides = runtime.store.loadOverrides(),
                    records = runtime.store.loadRecords(), credentialsSaved = runtime.vault.hasCredentials(),
                    hasVerifiedLiveOrder = settings.verifiedLiveAccountGeneration == settings.accountGeneration,
                    sessionLabel = credentials?.userId?.let { id ->
                        if (verifiedSession == VerifiedSession(id, settings.accountGeneration)) "로그인 확인 계정 ${maskId(id)}"
                        else "저장 계정 ${maskId(id)} · 로그인 대조 전"
                    } ?: "사이트 로그인 계정 · 신청 시 대조")
            }
            loaded = true
            mutableState.update { previous -> snapshot.copy(environment = previous.environment,
                lastEnvironmentCheck = previous.lastEnvironmentCheck, message = previous.message,
                sitePath = previous.sitePath, siteRequest = previous.siteRequest,
                busy = working || priorityStops > 0 || runtime.engine.active.value) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.update { it.copy(busy = working || priorityStops > 0 || !loaded, message = "저장 데이터를 읽지 못했습니다.") }
        }
    }

    private fun saveSettings(draft: AppSettings, sequence: Long) {
        planWrite(sequence) {
            val current = runtime.store.loadSettings()
            val next = draft.copy(liveScope = current.liveScope, liveTestDate = current.liveTestDate,
                displayPriceRiskAccepted = current.displayPriceRiskAccepted, accountGeneration = current.accountGeneration,
                verifiedLiveAccountGeneration = current.verifiedLiveAccountGeneration,
                liveBlockedReason = current.liveBlockedReason, generation = current.generation + 1,
                mediaAlarmEnabled = false,
                mediaVolumePercent = draft.mediaVolumePercent.coerceIn(0, 100),
                mediaDurationSeconds = draft.mediaDurationSeconds.coerceIn(1, 60))
            ReservationLimits.validate(next.copy(limitEnabled = false))?.let { throw UserFailure(it) }
            if (next.masterEnabled) {
                requireUser(current.liveScope != LiveScope.NONE && current.displayPriceRiskAccepted,
                    "실제구매 활성화 확인에서 범위와 가격 변동 위험에 먼저 동의하세요.")
                validateLiveSettings(next)
            }
            runtime.store.saveSettings(next)
        }
        changedAndReschedule()
        message("설정을 저장했습니다.")
    }

    private fun armLive(action: UiAction.ArmLive, sequence: Long) {
        planWrite(sequence) {
            requireUser(action.acceptedPriceRisk, "가격 변동과 실제 구매 위험에 먼저 동의하세요.")
            val current = runtime.store.loadSettings()
            validateLiveSettings(current)
            val today = LocalDate.now(ReservationScheduler.ZONE)
            requireUser(action.recurring || !action.date.isBefore(today), "지난 날짜는 실제구매 예약으로 활성화할 수 없습니다.")
            val overrides = runtime.store.loadOverrides()
            val plan = ScheduleCalculator.planFor(action.date, current, overrides[action.date])
                ?.takeIf { !it.date.isBefore(today) }
            val eligible = plan != null || (action.recurring &&
                ScheduleCalculator.upcomingPlans(current, overrides, today).isNotEmpty())
            requireUser(eligible, "활성화할 신청 계획이 없습니다. 근무 확인 또는 수동 날짜 계획을 저장하세요.")
            runtime.store.saveSettings(current.copy(masterEnabled = true,
                liveScope = if (action.recurring) LiveScope.RECURRING else LiveScope.SINGLE_DATE,
                liveTestDate = if (action.recurring) null else action.date,
                displayPriceRiskAccepted = true, generation = current.generation + 1))
        }
        changedAndReschedule()
        message(if (action.recurring) "반복 실제구매 예약을 활성화했습니다." else "${action.date} 실제구매 예약을 활성화했습니다.")
    }

    private fun validateLiveSettings(settings: AppSettings) {
        ReservationLimits.validate(settings)?.let { throw UserFailure(it) }
        requireUser(runtime.vault.load() != null, "실제구매에 사용할 계정을 먼저 저장하세요.")
        requireUser(settings.liveBlockedReason == null, "실제구매가 차단되어 있습니다. 주문내역과 차단 원인을 확인하세요.")
    }

    private fun reviewBlock(approved: Boolean, acknowledgedReason: String?) {
        requireUser(approved, "차단 사유와 기존 주문내역을 먼저 확인하고 해제에 동의하세요.")
        val snapshot = runtime.store.loadSettings()
        requireUser(acknowledgedReason != null && snapshot.liveBlockedReason == acknowledgedReason,
            "차단 사유가 변경되었거나 이미 해제되었습니다. 최신 사유를 다시 확인하세요.")
        val credentials = runtime.vault.load()
            ?: throw UserFailure("계정을 대조하려면 아이디를 먼저 저장하세요.")
        verifiedSession = null
        try { runtime.gateway.ensureSession(null) }
        catch (failure: SiteException) {
            if (failure.code != "LOGIN_REQUIRED" || !snapshot.useStoredCredentials) throw failure
            runtime.gateway.ensureSession(credentials)
        }
        runtime.gateway.verifyAccount(credentials.userId)
        runtime.store.updateSettings { current ->
            requireUser(current.accountGeneration == snapshot.accountGeneration,
                "확인 중 저장 계정이 변경되었습니다. 최신 계정으로 다시 확인하세요.")
            requireUser(current.liveBlockedReason == snapshot.liveBlockedReason,
                "확인 중 차단 사유가 변경되었습니다. 최신 사유를 다시 확인하세요.")
            current.copy(liveBlockedReason = null, generation = current.generation + 1)
        }
        verifiedSession = VerifiedSession(credentials.userId, snapshot.accountGeneration)
        changedAndReschedule()
        message("향후 새 구매의 차단만 해제했습니다. 기존 주문·확인필요 기록은 유지하며 재신청하거나 취소하지 않습니다. 다음 신청에서도 사이트 구조와 금액을 다시 검사합니다.")
    }

    private suspend fun saveCredentials(userId: String, password: String) {
        requireUser(userId.isNotBlank() && password.isNotEmpty(), "아이디와 비밀번호를 입력하세요.")
        val changedId = runCatching { runtime.vault.load()?.userId }.getOrNull() != userId.trim()
        stopForAccountChange()
        if (changedId) clearCookies()
        runtime.vault.save(userId, password)
        changedAndReschedule()
        message("계정을 저장했습니다. 실제구매 예약은 다시 동의해야 합니다.")
    }

    private fun stopForAccountChange() {
        verifiedSession = null
        synchronized(runtime.store) {
            val settings = runtime.store.loadSettings()
            runtime.store.saveSettings(settings.copy(masterEnabled = false, liveScope = LiveScope.NONE,
                liveTestDate = null, displayPriceRiskAccepted = false, accountGeneration = settings.accountGeneration + 1,
                verifiedLiveAccountGeneration = null, generation = settings.generation + 1))
        }
        runtime.scheduler.cancelAll()
        runtime.changed()
    }

    private suspend fun clearCookies() = withContext(Dispatchers.Main) {
        try {
            withTimeout(10_000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val manager = CookieManager.getInstance()
                    manager.removeAllCookies {
                        manager.flush()
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) { throw UserFailure("사이트 세션을 초기화하지 못했습니다. 다시 시도하세요.") }
    }

    private fun mockOrder(date: LocalDate) {
        val settings = runtime.store.loadSettings()
        ReservationLimits.validate(settings)?.let { throw UserFailure(it) }
        val plan = ScheduleCalculator.planFor(date, settings, runtime.store.loadOverrides()[date])
            ?: throw UserFailure("이 날짜에 신청 계획이 없습니다.")
        message("모의 계획 검사 통과: ${plan.date}, ${plan.quantity}개, ${plan.time}. 서버 접속·실제 신청·주문완료 기록은 수행하지 않았습니다.")
    }

    private fun advanceGeneration() {
        synchronized(runtime.store) {
            val settings = runtime.store.loadSettings()
            runtime.store.saveSettings(settings.copy(generation = settings.generation + 1))
        }
    }

    private fun changedAndReschedule() {
        runtime.changed()
        runtime.scheduler.reschedule()
    }

    private fun message(value: String) { mutableState.update { it.copy(message = value) } }
    private fun requireUser(condition: Boolean, message: String) { if (!condition) throw UserFailure(message) }
    private class UserFailure(message: String) : Exception(message)
    private data class VerifiedSession(val userId: String, val accountGeneration: Long)

    private fun maskId(value: String): String = if (value.length <= 2) "**" else value.take(2) + "*".repeat(minOf(value.length - 2, 8))
    private fun safeSitePath(path: String): String = if (path.startsWith("/") && !path.startsWith("//") &&
        !path.contains('\\') && !path.contains('\r') && !path.contains('\n')) path else "/"
}
