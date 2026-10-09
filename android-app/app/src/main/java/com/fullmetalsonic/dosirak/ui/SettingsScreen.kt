package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.domain.*
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(state: UiState, onAction: (UiAction) -> Unit, onDate: (LocalDate) -> Unit,
    onSite: () -> Unit, onCalendar: () -> Unit = {}, entryRequest: Int = 0) {
    var draft by rememberSaveable(stateSaver = SettingsDraftSaver) { mutableStateOf(state.settings.copy(masterEnabled = false)) }
    var anchor by rememberSaveable { mutableStateOf(state.settings.patternAnchor.toString()) }
    var quantity by rememberSaveable { mutableStateOf(state.settings.defaultQuantity.toString()) }
    var time by rememberSaveable { mutableStateOf(state.settings.orderTime.format(TimeFormat)) }
    var p by rememberSaveable { mutableStateOf(state.settings.unitLimit?.toString() ?: "") }
    var c by rememberSaveable { mutableStateOf(state.settings.orderLimit?.toString() ?: "") }
    var retries by rememberSaveable { mutableStateOf(state.settings.retryCount.toString()) }
    var interval by rememberSaveable { mutableStateOf(state.settings.retryIntervalSeconds.toString()) }
    var observedGeneration by rememberSaveable { mutableLongStateOf(state.settings.generation) }
    var step by rememberSaveable { mutableIntStateOf(if (state.settings.masterEnabled) 4 else 0) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var reviewBlock by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<AppSettings?>(null) }
    var pendingActivation by remember { mutableStateOf(false) }
    var pendingMessage by remember { mutableStateOf<String?>(null) }
    var savedLabel by remember { mutableStateOf<String?>(null) }
    var reviewState by remember { mutableStateOf<UiState?>(null) }
    var snapshot by remember { mutableStateOf<ActivationSnapshot?>(null) }
    var changedDuringReview by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val candidate = draft.copy(masterEnabled = false, patternAnchor = parseDate(anchor) ?: draft.patternAnchor,
        defaultQuantity = quantity.toIntOrNull() ?: 0, orderTime = parseTime(time) ?: draft.orderTime,
        unitLimit = p.toLongOrNull(), orderLimit = c.toLongOrNull(), retryCount = retries.toIntOrNull() ?: -1,
        retryIntervalSeconds = interval.toIntOrNull() ?: 0, generation = state.settings.generation,
        accountGeneration = state.settings.accountGeneration, verifiedLiveAccountGeneration = state.settings.verifiedLiveAccountGeneration,
        liveBlockedReason = state.settings.liveBlockedReason, liveScope = state.settings.liveScope,
        liveTestDate = state.settings.liveTestDate, displayPriceRiskAccepted = state.settings.displayPriceRiskAccepted)
    val error = when { parseDate(anchor) == null -> "근무 기준일을 확인하세요."; parseTime(time) == null -> "신청 시각을 HH:mm:ss로 입력하세요."; !validOrderTime(candidate.orderTime) -> "신청 시각은 06:00:00~07:59:59입니다."; else -> ReservationLimits.validate(candidate) }
    val issues = setupIssues(candidate, state).toMutableList().apply { if (parseTime(time) == null) add(SetupIssue("신청 시각 입력", 2)); if (parseDate(anchor) == null) add(SetupIssue("근무 기준일 확인", 1)) }.distinct()
    val dirty = candidate.editableOnly() != state.settings.editableOnly() || parseTime(time) == null || parseDate(anchor) == null
    fun loadSaved() {
        draft = state.settings.copy(masterEnabled = false); anchor = state.settings.patternAnchor.toString()
        quantity = state.settings.defaultQuantity.toString(); time = state.settings.orderTime.format(TimeFormat)
        p = state.settings.unitLimit?.toString() ?: ""; c = state.settings.orderLimit?.toString() ?: ""
        retries = state.settings.retryCount.toString(); interval = state.settings.retryIntervalSeconds.toString()
        observedGeneration = state.settings.generation
    }
    LaunchedEffect(entryRequest) { if (entryRequest > 0) step = issues.firstOrNull { it.step >= 0 }?.step ?: 4 }
    LaunchedEffect(state.settings.generation, state.settings.accountGeneration, state.loginVerified, state.message) {
        pendingSave?.let { requested ->
            val executionMatches = if (pendingActivation) state.settings.masterEnabled && state.settings.liveScope == LiveScope.RECURRING else requested.masterEnabled == state.settings.masterEnabled
            if (state.settings.generation != observedGeneration && executionMatches && requested.editableOnly() == state.settings.editableOnly()) {
                if (candidate.editableOnly() == requested.editableOnly() && parseTime(time) != null && parseDate(anchor) != null) {
                    loadSaved(); savedLabel = "설정 저장됨"
                } else { observedGeneration = state.settings.generation; savedLabel = "설정 저장됨 · 추가 편집 내용 유지" }
                pendingSave = null
            } else if (state.message != null && state.message != pendingMessage) {
                pendingSave = null; savedLabel = "입력값 유지 · 저장 결과 확인 필요"
            }
        }
        snapshot?.let { held ->
            if (held.expectedGeneration != state.settings.generation || held.expectedAccountGeneration != state.settings.accountGeneration) {
                snapshot = null; reviewState = null; changedDuringReview = true
            }
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${step + 1} / 5 · ${listOf("계정", "근무표", "주문 조건", "휴대폰 설정", "시작 확인")[step]}", style = MaterialTheme.typography.titleLarge)
        LinearProgressIndicator(progress = { (step + 1) / 5f }, modifier = Modifier.fillMaxWidth())
        if (state.settings.masterEnabled || state.busy) {
            Text(if (state.settings.masterEnabled) "자동주문 켜짐" else "신청 처리 중", color = MaterialTheme.colorScheme.primary)
            OutlinedButton(onClick = { onAction(UiAction.StopAutomatic) }, modifier = Modifier.fillMaxWidth()) { Text("자동주문 중지") }
            Text("미래 실행과 아직 전송하지 않은 신청을 중지합니다. 사이트 주문을 취소하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        }
        state.registrationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (dirty) Text("편집 중 · 아직 저장되지 않음", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        savedLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        if (state.settings.generation != observedGeneration && pendingSave == null) {
            Text("저장된 설정이 변경되었습니다. 입력 중인 값은 유지됩니다.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { loadSaved(); savedLabel = null }) { Text("저장된 설정 다시 불러오기") }
        }
        when (step) {
            0 -> AccountSettings(state, onAction, onSite, draft.useStoredCredentials) { draft = draft.copy(useStoredCredentials = it) }
            1 -> WorkSetupScreen(candidate, anchor, onAnchor = { anchor = it; draft = draft.copy(patternConfirmed = false) }, onSettings = { draft = it })
            2 -> {
                QuantityControl(quantity) { quantity = it }
                Text("자동주문 시각 (HH:mm:ss)", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(time, { time = it }, singleLine = true, isError = parseTime(time)?.let { !validOrderTime(it) } ?: true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "자동주문 시각 (HH:mm:ss)" })
                Text("신청 가능 시각 06:00:00~07:59:59", style = MaterialTheme.typography.bodySmall)
                ToggleRow("금액 한도 사용", draft.limitEnabled, { draft = draft.copy(limitEnabled = it) })
                if (draft.limitEnabled) {
                    SetupNumberField("도시락 1개 최대금액 (원)", p, { p = it }, (p.toLongOrNull() ?: 0) <= 0)
                    SetupNumberField("한 번에 주문할 최대금액 (원)", c, { c = it }, (c.toLongOrNull() ?: 0) <= 0)
                    Text("${quantity.toIntOrNull() ?: 0}개 주문 최대금액: ${appliedLimit(candidate, quantity.toIntOrNull() ?: 0)?.let { "${it}원" } ?: "금액 입력 필요"}", color = MaterialTheme.colorScheme.primary)
                } else Text("금액 한도 사용 안 함 · 가격 상한 보호 없음", color = MaterialTheme.colorScheme.error)
            }
            3 -> EnvironmentSetupScreen(state, candidate, onSettings = { draft = it }, onAction = onAction, onReviewBlock = { reviewBlock = true })
            else -> {
                RecurringSummary(candidate, state)
                if (changedDuringReview) Text("계정 또는 설정이 변경되었습니다. 내용을 다시 확인하고 동의하세요.", color = MaterialTheme.colorScheme.error)
                if (issues.isNotEmpty()) {
                    Text("시작 전에 확인할 항목", style = MaterialTheme.typography.titleMedium)
                    issues.forEach { issue -> OutlinedButton(onClick = { if (issue.step < 0) onCalendar() else step = issue.step }, modifier = Modifier.fillMaxWidth()) { Text(issue.label) } }
                }
                Button(onClick = {
                    focus.clearFocus(); keyboard?.hide()
                    val fixed = candidate.copy(weekdays = candidate.weekdays.toSet())
                    snapshot = ActivationSnapshot(fixed, state.settings.generation, state.settings.accountGeneration, state.accountLabel)
                    reviewState = state.copy(settings = fixed, overrides = state.overrides.toMap(), records = state.records.toList(), environment = state.environment.toList())
                    changedDuringReview = false
                }, enabled = issues.isEmpty() && error == null && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("자동주문 시작") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step > 0) OutlinedButton(onClick = { focus.clearFocus(); keyboard?.hide(); step-- }, modifier = Modifier.weight(1f)) { Text("이전") }
            if (step < 4) Button(onClick = { focus.clearFocus(); keyboard?.hide(); step++ }, modifier = Modifier.weight(1f)) { Text("다음") }
        }
        if (step == 4) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = { val saved = candidate.copy(masterEnabled = state.settings.masterEnabled); pendingActivation = false; pendingMessage = state.message; pendingSave = saved; savedLabel = "저장 확인 중"; onAction(UiAction.SaveSettings(saved, state.settings.generation)) }, enabled = error == null && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("설정만 저장") }
            Text(if (state.settings.masterEnabled) "저장된 설정으로 다음 예약을 다시 확인합니다." else "설정을 저장해도 자동주문은 켜지지 않습니다.", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { advanced = true }, modifier = Modifier.fillMaxWidth()) { Text("고급 설정") }
    }
    if (advanced) ModalBottomSheet(onDismissRequest = { advanced = false }) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("고급 설정", style = MaterialTheme.typography.titleLarge)
            SetupNumberField("주문 실패 후 다시 시도 횟수 (0~10회)", retries, { retries = it }, retries.toIntOrNull() !in 0..10)
            SetupNumberField("다시 시도까지 기다리는 시간 (1~300초)", interval, { interval = it }, interval.toIntOrNull() !in 1..300)
            Text("제출 결과가 불확실하면 다시 주문하지 않고 조회만 합니다.", style = MaterialTheme.typography.bodySmall)
            ToggleRow("준비 알림", draft.preparationAlert, { draft = draft.copy(preparationAlert = it) })
            ToggleRow("실패 미디어 경보", draft.mediaAlarmEnabled, { draft = draft.copy(mediaAlarmEnabled = it) }, if (!state.mediaSupported) "미지원 · 화면 꺼짐 재생·음량 복원 미검증" else null, state.mediaSupported)
            if (state.mediaSupported && draft.mediaAlarmEnabled) {
                Text("음량 ${draft.mediaVolumePercent}%"); Slider(draft.mediaVolumePercent.toFloat(), { draft = draft.copy(mediaVolumePercent = it.toInt()) }, valueRange = 0f..100f)
                Text("재생 ${draft.mediaDurationSeconds}초"); Slider(draft.mediaDurationSeconds.toFloat(), { draft = draft.copy(mediaDurationSeconds = it.toInt()) }, valueRange = 1f..60f, steps = 58)
            }
            OutlinedButton(onClick = { onAction(UiAction.TestSound) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("실패 알림음 시험") }
            OutlinedButton(onClick = { onAction(UiAction.ExportBackup) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("설정 백업 내보내기") }
            OutlinedButton(onClick = { onAction(UiAction.ImportBackup) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("백업 가져오기") }
            Text("계정 비밀번호는 백업에서 제외됩니다.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { advanced = false }, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
        }
    }
    snapshot?.let { held -> reviewState?.let { fixedState ->
        RecurringConsentDialog(held, fixedState, onDismiss = { snapshot = null; reviewState = null }, onConfirm = {
            pendingActivation = true; pendingMessage = state.message; pendingSave = held.settings; onAction(UiAction.SaveAndArmRecurring(held, acceptedPriceRisk = true)); snapshot = null; reviewState = null
        })
    } }
    if (reviewBlock) state.settings.liveBlockedReason?.let { reason -> BlockReviewDialog(reason, !state.busy, onDismiss = { reviewBlock = false }, onConfirm = { onAction(UiAction.ReviewBlock(true)); reviewBlock = false }) }
}
