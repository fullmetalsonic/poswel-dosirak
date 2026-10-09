package com.fullmetalsonic.dosirak

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import com.fullmetalsonic.dosirak.domain.*
import com.fullmetalsonic.dosirak.platform.ReservationScheduler
import com.fullmetalsonic.dosirak.platform.RegistrationCode
import com.fullmetalsonic.dosirak.platform.RegistrationResult
import com.fullmetalsonic.dosirak.platform.BackgroundCheckStatus
import com.fullmetalsonic.dosirak.platform.NotificationTestCode
import com.fullmetalsonic.dosirak.runtime.AppRuntime
import com.fullmetalsonic.dosirak.site.SiteException
import com.fullmetalsonic.dosirak.ui.ActivationSnapshot
import com.fullmetalsonic.dosirak.ui.UiAction
import com.fullmetalsonic.dosirak.ui.UiState
import com.fullmetalsonic.dosirak.ui.SettingsSaveResult
import com.fullmetalsonic.dosirak.update.VersionChecker
import com.fullmetalsonic.dosirak.web.LoginCheckCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.time.Instant
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
    private val versionChecker = VersionChecker()
    @Suppress("DEPRECATION")
    private val currentVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    private var checkingUpdate = false
    @Volatile private var verifiedSession: VerifiedSession? = null
    @Volatile var loginDiagnosticCode: LoginCheckCode = LoginCheckCode.UNKNOWN
        private set

    init {
        checkUpdate()
        scope.launch { runtime.updates.collect { reload() } }
        scope.launch { runtime.engine.active.collect { active ->
            mutableState.update { it.copy(busy = working || priorityStops > 0 || !loaded || active) }
        } }
    }

    fun onAction(action: UiAction) {
        when (action) {
            UiAction.ClearMessage -> mutableState.update { it.copy(message = null, transientMessage = false) }
            UiAction.CompleteOnboarding -> work {
                runtime.onboarding.complete()
            }
            is UiAction.OpenSite -> mutableState.update {
                it.copy(sitePath = safeSitePath(action.path), siteRequest = it.siteRequest + 1)
            }
            is UiAction.OpenEnvironment -> {
                if (!runtime.environment.open(action.key)) message("Android 설정 화면을 열지 못했습니다.")
            }
            UiAction.ExportBackup -> requestExport()
            UiAction.ImportBackup -> if (state.value.busy) message("진행 중인 작업이 끝난 뒤 백업을 가져오세요.") else requestImport()
            UiAction.RefreshEnvironment -> refreshEnvironment()
            UiAction.CheckBackgroundNow -> work {
                var registration: RegistrationResult? = null
                val result = runtime.backgroundCheck.checkNow {
                    runtime.scheduler.reschedule().also { registration = it }
                }
                updateCheckState(registration ?: result, runtime.backgroundCheck.status())
                message(result.message, transient = true)
            }
            UiAction.TestSound -> notificationTestMessage(runtime.notifier.testSound())
            is UiAction.TestNotification -> notificationTestMessage(runtime.notifier.test(action.event, action.preferences))
            is UiAction.OpenNotificationSettings -> runCatching {
                context.startActivity(runtime.notifier.notificationSettingsIntent(action.event).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { message("Android 알림 설정을 열지 못했습니다.", transient = true) }
            UiAction.CheckUpdate -> checkUpdate()
            UiAction.OpenRelease -> {
                val url = state.value.updateStatus.releaseUrl
                if (VersionChecker.isAllowedReleaseUrl(url)) runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { message("배포 페이지를 열지 못했습니다.") }
            }
            is UiAction.SaveSettings -> {
                val masterOff = state.value.settings.masterEnabled && !action.settings.masterEnabled
                val dayOff = state.value.settings.dayAutoEnabled && !action.settings.dayAutoEnabled
                if (isBusy() && (masterOff || dayOff)) priorityStop {
                    val latest = runtime.store.loadSettings()
                    ActivationRules.requireGeneration(latest, action.expectedGeneration)
                    runtime.store.saveSettings(latest.copy(
                        masterEnabled = if (masterOff) false else latest.masterEnabled,
                        dayAutoEnabled = if (dayOff) false else latest.dayAutoEnabled,
                        generation = latest.generation + 1))
                } else work(settingsSaveRequestId = action.requestId) { sequence -> saveSettings(action.settings, action.expectedGeneration, sequence, action.requestId) }
            }
            is UiAction.SaveAndArmRecurring -> work { sequence ->
                saveAndArmRecurring(action.snapshot, action.acceptedPriceRisk, sequence)
            }
            UiAction.StopAutomatic -> priorityStop {
                runtime.store.updateSettings { ActivationRules.stoppedSettings(it) }
            }
            is UiAction.SaveDate -> {
                if (isBusy() && action.value.policy == DatePolicy.EXCLUDE) priorityStop {
                    runtime.store.excludeOverrideAndAdvanceGeneration(action.value)
                } else work { sequence ->
                    planWrite(sequence) {
                        requireUser(action.value.quantity == null || action.value.quantity in 1..5, "수량은 1~5개로 입력하세요.")
                        runtime.store.saveOverrideAndAdvanceGeneration(action.value)
                    }
                    mutableState.update { it.copy(orderLookups = it.orderLookups - action.value.date) }
                    changedAndReschedule()
                    message(if (action.value.policy == DatePolicy.EXCLUDE) "예약을 취소했습니다." else "예약을 저장했습니다.", transient = true)
                }
            }
            is UiAction.RestoreDate -> work { sequence ->
                planWrite(sequence) {
                    runtime.store.deleteOverrideAndAdvanceGeneration(action.date)
                }
                mutableState.update { it.copy(orderLookups = it.orderLookups - action.date) }
                changedAndReschedule()
                message("근무표에 따른 예약으로 되돌렸습니다.", transient = true)
            }
            is UiAction.SaveCredentials -> work { saveCredentials(action.userId, action.password) }
            UiAction.DeleteCredentials -> work {
                stopForAccountChange()
                runtime.vault.clear()
                clearCookies()
                changedAndReschedule()
                message("저장 계정을 삭제하고 실제구매 예약을 해제했습니다.")
            }
            UiAction.CheckLogin -> work { loginCheckDiagnostics {
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
                    val loginOnlyBlock = LoginRecoveryPolicy.canRecover(reason, true,
                        settings.accountGeneration, latest.accountGeneration)
                    if (loginOnlyBlock) {
                        runtime.store.saveSettings(latest.copy(liveBlockedReason = null, generation = latest.generation + 1))
                    }
                    reason to loginOnlyBlock
                }
                if (loginOnlyBlock) changedAndReschedule()
                mutableState.update { it.copy(sitePath = "/", siteRequest = it.siteRequest + 1) }
                val unresolvedBlock = reason != null && !loginOnlyBlock
                message(if (unresolvedBlock) "로그인은 정상입니다. 자동주문 중지 사유는 휴대폰 설정에서 확인하세요."
                    else "로그인 확인을 완료했습니다.", transient = !unresolvedBlock)
            } }
            is UiAction.ReviewBlock -> {
                val acknowledgedReason = state.value.settings.liveBlockedReason
                work { reviewBlock(action.approved, acknowledgedReason) }
            }
            is UiAction.MockOrder -> work { mockOrder(action.date) }
            is UiAction.OrderNow -> work {
                requireUser(action.acceptedPriceRisk, "실제 구매와 가격 변동 위험에 먼저 동의하세요.")
                requireUser(action.expectedGeneration != null && action.expectedAccountGeneration != null,
                    "확인한 설정과 계정 정보가 없습니다. 실제 신청 확인을 다시 열고 동의하세요.")
                val result = engineMutex.withLock {
                    runtime.engine.execute(action.date, manual = true, acceptedPriceRisk = true,
                        expectedGeneration = action.expectedGeneration,
                        expectedAccountGeneration = action.expectedAccountGeneration)
                }
                updateRegistration()
                message(result.message)
            }
            is UiAction.RefreshOrders -> work {
                val result = engineMutex.withLock { runtime.engine.refreshOrders(action.date) }
                mutableState.update { it.copy(orderLookups = it.orderLookups + (action.date to result)) }
                updateRegistration()
                message(if (result.stage == "LOOKUP_ERROR") "주문내역을 불러오지 못했습니다." else "조회 결과를 갱신했습니다.", transient = true)
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
                message("백업을 저장했습니다.")
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
        runtime.onboarding.complete()
        changedAndReschedule()
        message("백업을 가져왔습니다. 자동주문은 꺼져 있습니다.")
    }

    fun notificationPermissionNeeded() {
        message("앱 알림이 꺼져 있습니다. 알림을 켠 뒤 시험을 다시 눌러 주세요.", transient = true)
    }

    private fun notificationTestMessage(code: NotificationTestCode) {
        message(when (code) {
            NotificationTestCode.SENT -> "시험 알림을 보냈습니다. 표시·소리는 휴대폰 설정을 따릅니다."
            NotificationTestCode.EVENT_DISABLED -> "이 알림이 꺼져 있어 시험하지 않았습니다. 해당 알림을 켜 주세요."
            NotificationTestCode.APP_DISABLED -> "앱 알림이 꺼져 있어 시험하지 않았습니다. 알림을 켠 뒤 다시 시험하세요."
            NotificationTestCode.CHANNEL_BLOCKED -> "휴대폰에서 이 알림 채널이 차단되어 있습니다. 세부 설정의 ‘이 알림의 휴대폰 설정’에서 확인하세요."
            NotificationTestCode.FAILED -> "시험 알림을 보내지 못했습니다. 휴대폰 알림 설정을 확인하고 다시 시험하세요."
        }, transient = true)
    }

    fun refreshEnvironment() {
        scope.launch {
            try {
                val environment = withContext(Dispatchers.IO) {
                    updateRegistration()
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

    fun resumeInterruptedPreparation(activity: Activity) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runtime.scheduler.resumeInterruptedPreparation(activity)
                } ?: return@launch
                updateCheckState(result, runtime.backgroundCheck.status())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message("중단된 준비 작업을 복구하지 못했습니다. 실행 상태를 확인하세요.") }
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
                message("자동 신청을 중지했습니다.", transient = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ActivationRejected) { message(failure.message ?: "최신 설정을 확인하세요.") }
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

    private fun work(settingsSaveRequestId: Long? = null, block: suspend (Long) -> Unit) {
        if (isBusy()) {
            settingsSaveRequestId?.let { id -> mutableState.update { it.copy(settingsSaveResult = SettingsSaveResult(id, false, "진행 중인 작업이 끝난 뒤 다시 저장하세요.")) } }
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
            catch (failure: ActivationRejected) { message(failure.message ?: "활성화 조건을 확인하세요.") }
            catch (_: Exception) { message("작업을 완료하지 못했습니다. 로그인·입력값·인터넷 연결을 확인하세요.") }
            finally {
                settingsSaveRequestId?.let { id -> mutableState.update {
                    if (it.settingsSaveResult?.requestId == id) it else it.copy(settingsSaveResult = SettingsSaveResult(id, false, it.message))
                } }
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
                val accountLabel = credentials?.userId?.let(ActivationRules::maskAccount).orEmpty()
                val loginVerified = credentials != null && verifiedSession == VerifiedSession(credentials.userId, settings.accountGeneration)
                UiState(settings = settings, overrides = runtime.store.loadOverrides(),
                    onboardingRequired = runtime.onboarding.isRequired(),
                    records = runtime.store.loadRecords(), credentialsSaved = runtime.vault.hasCredentials(),
                    accountLabel = accountLabel, loginVerified = loginVerified,
                    hasVerifiedLiveOrder = settings.verifiedLiveAccountGeneration == settings.accountGeneration,
                    sessionLabel = credentials?.userId?.let { id ->
                        if (verifiedSession == VerifiedSession(id, settings.accountGeneration)) "로그인 확인 계정 ${maskId(id)}"
                        else "저장 계정 ${maskId(id)} · 로그인 대조 전"
                    } ?: "사이트 로그인 계정 · 신청 시 대조")
            }
            loaded = true
            mutableState.update { previous -> snapshot.copy(environment = previous.environment,
                lastEnvironmentCheck = previous.lastEnvironmentCheck, message = previous.message,
                transientMessage = previous.transientMessage,
                orderLookups = if (previous.settings.accountGeneration == snapshot.settings.accountGeneration) previous.orderLookups else emptyMap(),
                registrationMessage = previous.registrationMessage,
                registrationProblem = previous.registrationProblem,
                settingsSaveResult = previous.settingsSaveResult,
                backgroundLastCheck = previous.backgroundLastCheck,
                backgroundNextCheck = previous.backgroundNextCheck,
                backgroundCheckSummary = previous.backgroundCheckSummary,
                currentVersion = currentVersion,
                updateStatus = previous.updateStatus,
                sitePath = previous.sitePath, siteRequest = previous.siteRequest,
                busy = working || priorityStops > 0 || runtime.engine.active.value) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.update { it.copy(busy = working || priorityStops > 0 || !loaded, message = "저장 데이터를 읽지 못했습니다.", transientMessage = false) }
        }
    }

    private fun saveSettings(draft: AppSettings, expectedGeneration: Long?, sequence: Long, requestId: Long) {
        planWrite(sequence) {
            val current = runtime.store.loadSettings()
            val next = ActivationRules.settingsForSave(current, draft, expectedGeneration)
            if (next.masterEnabled) validateLiveSettings(next)
            runtime.store.saveSettings(next)
        }
        mutableState.update { it.copy(settingsSaveResult = SettingsSaveResult(requestId, true)) }
        val registration = runCatching { changedAndReschedule() }.getOrNull()
        message(if (registration == null) "설정은 저장했지만 예약 등록을 확인하지 못했습니다."
            else "설정을 저장했습니다.", transient = registration != null)
    }

    private fun saveAndArmRecurring(snapshot: ActivationSnapshot, acceptedPriceRisk: Boolean, sequence: Long) {
        planWrite(sequence) {
            val current = runtime.store.loadSettings()
            val next = ActivationRules.recurringActivation(current, snapshot.settings,
                snapshot.expectedGeneration, snapshot.expectedAccountGeneration, snapshot.accountLabel,
                runtime.vault.load()?.userId, acceptedPriceRisk, runtime.store.loadOverrides(),
                runtime.store.loadRecords(), Instant.now())
            runtime.store.saveSettings(next)
        }
        val registration = changedAndReschedule()
        message(activationMessage("확인한 설정을 저장하고 반복 실제구매를 ON으로 설정했습니다.", registration))
    }

    private fun armLive(action: UiAction.ArmLive, sequence: Long) {
        planWrite(sequence) {
            requireUser(action.acceptedPriceRisk, "가격 변동과 실제 구매 위험에 먼저 동의하세요.")
            val current = runtime.store.loadSettings()
            requireUser(action.expectedGeneration != null && action.expectedAccountGeneration != null,
                "확인한 설정과 계정 정보가 없습니다. 동의 후 시작 화면을 다시 확인하세요.")
            ActivationRules.requireGeneration(current, action.expectedGeneration)
            requireUser(current.accountGeneration == action.expectedAccountGeneration,
                "저장 계정이 변경되었습니다. 최신 계정으로 동의 후 시작을 다시 확인하세요.")
            validateLiveSettings(current)
            val overrides = runtime.store.loadOverrides()
            val next = current.copy(masterEnabled = true,
                liveScope = if (action.recurring) LiveScope.RECURRING else LiveScope.SINGLE_DATE,
                liveTestDate = if (action.recurring) null else action.date,
                displayPriceRiskAccepted = true, generation = current.generation + 1)
            requireUser(ActivationRules.nextExecutablePlan(next, overrides, runtime.store.loadRecords(), Instant.now()) != null,
                "06:00 이상 08:00 미만에 실행할 미래 신청 계획이 없습니다. 지난 신청 시각과 주문내역을 확인하세요.")
            runtime.store.saveSettings(next)
        }
        val registration = changedAndReschedule()
        message(activationMessage(if (action.recurring) "반복 실제구매를 ON으로 설정했습니다."
            else "${action.date} 실제구매를 ON으로 설정했습니다.", registration))
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

    private suspend fun loginCheckDiagnostics(check: suspend () -> Unit) {
        loginDiagnosticCode = LoginCheckCode.CHECKING
        try {
            check()
            loginDiagnosticCode = LoginCheckCode.VERIFIED
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: SiteException) {
            loginDiagnosticCode = LoginCheckCode.fromSiteCode(failure.code)
            throw UserFailure(when (failure.code) {
                "LOGIN_REQUIRED" -> "사이트 로그인이 필요합니다. 저장 계정 사용 설정 또는 사이트 로그인을 확인하세요."
                "LOGIN_FAILED" -> "사이트 로그인을 완료하지 못했습니다. 저장한 아이디·비밀번호를 확인하세요."
                "LOGIN_CONTRACT", "SESSION_CONTRACT" -> "사이트 로그인 화면의 구조를 확인하지 못했습니다. 사이트 화면과 앱 업데이트를 확인하세요."
                "ACCOUNT_UNVERIFIED" -> "로그인은 확인했지만 사이트 계정정보를 대조하지 못했습니다. 사이트 계정 화면을 확인하세요."
                "ACCOUNT_MISMATCH" -> "사이트 로그인 계정과 앱의 저장 계정이 다릅니다. 계정을 확인하세요."
                "SECURITY_BLOCK_CONTRACT" -> "사이트 보안 차단 화면이 확인되었습니다. 잠시 후 사이트 접속 상태를 확인하세요."
                "NETWORK" -> "사이트에 연결하지 못했습니다. 인터넷 연결과 사이트 접속 상태를 확인하세요."
                else -> "사이트 로그인 응답을 확인하지 못했습니다. 사이트 접속 상태를 확인하세요."
            })
        } catch (failure: Exception) {
            loginDiagnosticCode = LoginCheckCode.OTHER
            throw failure
        } finally {
            Log.i("PoswelLoginNative", "code=${loginDiagnosticCode.name}")
        }
    }

    private suspend fun saveCredentials(userId: String, password: String) {
        requireUser(userId.isNotBlank() && password.isNotEmpty(), "아이디와 비밀번호를 입력하세요.")
        val changedId = runCatching { runtime.vault.load()?.userId }.getOrNull() != userId.trim()
        stopForAccountChange()
        if (changedId) clearCookies()
        runtime.vault.save(userId, password)
        changedAndReschedule()
        message("계정을 저장했습니다. 자동주문을 다시 켜 주세요.", transient = true)
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

    private fun changedAndReschedule(): RegistrationResult {
        runtime.changed()
        return updateRegistration()
    }

    private fun updateRegistration(): RegistrationResult = runtime.scheduler.reschedule().also { result ->
        runtime.backgroundCheck.reschedule()
        updateCheckState(result, runtime.backgroundCheck.status())
    }

    private fun updateCheckState(result: RegistrationResult?, background: BackgroundCheckStatus) {
        mutableState.update { it.copy(registrationMessage = result?.message ?: it.registrationMessage,
            registrationProblem = result?.let { registration -> registration.code == RegistrationCode.BLOCKED || registration.code == RegistrationCode.FAILED } ?: it.registrationProblem,
            backgroundLastCheck = background.lastCheckEpochMillis?.takeIf { epoch -> epoch > 0 }?.let(::formatCheckTime) ?: "아직 점검하지 않음",
            backgroundNextCheck = background.nextCheckEpochMillis?.takeIf { epoch -> epoch > 0 }?.let(::formatCheckTime) ?: "점검 꺼짐",
            backgroundCheckSummary = background.summary) }
    }

    private fun formatCheckTime(epoch: Long): String = Instant.ofEpochMilli(epoch).atZone(ReservationScheduler.ZONE)
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))

    private fun checkUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        mutableState.update { it.copy(currentVersion = currentVersion, updateStatus = it.updateStatus.copy(checking = true)) }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { versionChecker.check(currentVersion) }
                mutableState.update { it.copy(updateStatus = result) }
            } finally { checkingUpdate = false }
        }
    }

    private fun activationMessage(saved: String, result: RegistrationResult): String = when (result.code) {
        RegistrationCode.REGISTERED -> "$saved ${result.message} 주문 접수 결과는 실행 후 내역에서 확인합니다."
        RegistrationCode.IN_FLIGHT -> "$saved ${result.message}"
        RegistrationCode.BLOCKED, RegistrationCode.FAILED -> "$saved 알람 등록에 문제가 있습니다: ${result.message} 자동실행 ON 설정은 유지됩니다."
        RegistrationCode.NO_FUTURE_PLAN -> "$saved 현재 등록할 미래 계획이 없습니다. ${result.message}"
        RegistrationCode.STOPPED -> "$saved ${result.message}"
    }

    private fun message(value: String, transient: Boolean = false) {
        mutableState.update { it.copy(message = value, transientMessage = transient) }
    }
    private fun requireUser(condition: Boolean, message: String) { if (!condition) throw UserFailure(message) }
    private class UserFailure(message: String) : Exception(message)
    private data class VerifiedSession(val userId: String, val accountGeneration: Long)

    private fun maskId(value: String): String = ActivationRules.maskAccount(value)
    private fun safeSitePath(path: String): String = if (path.startsWith("/") && !path.startsWith("//") &&
        !path.contains('\\') && !path.contains('\r') && !path.contains('\n')) path else "/"
}
