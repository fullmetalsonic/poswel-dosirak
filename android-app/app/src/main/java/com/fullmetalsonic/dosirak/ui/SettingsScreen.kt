package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.domain.*
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
internal fun SettingsScreen(state: UiState, onAction: (UiAction) -> Unit, onDate: (LocalDate) -> Unit, onSite: () -> Unit) {
    var draft by rememberSaveable(state.settings, stateSaver = SettingsDraftSaver) { mutableStateOf(state.settings) }
    var anchor by rememberSaveable(state.settings.patternAnchor.toString()) { mutableStateOf(state.settings.patternAnchor.toString()) }
    var quantity by rememberSaveable(state.settings.defaultQuantity) { mutableStateOf(state.settings.defaultQuantity.toString()) }
    var time by rememberSaveable(state.settings.orderTime.toString()) { mutableStateOf(state.settings.orderTime.format(TimeFormat)) }
    var p by rememberSaveable(state.settings.unitLimit) { mutableStateOf(state.settings.unitLimit?.toString() ?: "") }
    var c by rememberSaveable(state.settings.orderLimit) { mutableStateOf(state.settings.orderLimit?.toString() ?: "") }
    var retries by rememberSaveable(state.settings.retryCount) { mutableStateOf(state.settings.retryCount.toString()) }
    var interval by rememberSaveable(state.settings.retryIntervalSeconds) { mutableStateOf(state.settings.retryIntervalSeconds.toString()) }
    val today = todaySeoul()
    var testDate by rememberSaveable { mutableStateOf((state.settings.liveTestDate ?: today.plusDays(1)).toString()) }
    var liveConfirm by remember { mutableStateOf(false) }
    var recurring by remember { mutableStateOf(false) }
    var reviewBlock by remember { mutableStateOf(false) }
    val date = parseDate(testDate)
    val plan = date?.let { ScheduleCalculator.planFor(it, state.settings, state.overrides[it]) }
    val upcoming = ScheduleCalculator.upcomingPlans(state.settings, state.overrides, today)
    val recurringPlan = if (upcoming.isEmpty()) null else plan?.takeIf { it.date >= today } ?: upcoming.first()
    val candidate = draft.copy(patternAnchor = parseDate(anchor) ?: draft.patternAnchor, defaultQuantity = quantity.toIntOrNull() ?: 0, orderTime = parseTime(time) ?: draft.orderTime, unitLimit = p.toLongOrNull(), orderLimit = c.toLongOrNull(), retryCount = retries.toIntOrNull() ?: -1, retryIntervalSeconds = interval.toIntOrNull() ?: 0)
    val error = when { parseDate(anchor) == null -> "기준일은 YYYY-MM-DD로 입력하세요."; parseTime(time) == null -> "신청 시각은 HH:mm:ss로 입력하세요."; else -> ReservationLimits.validate(candidate) }
    val dirty = candidate != state.settings || time != state.settings.orderTime.format(TimeFormat) || anchor != state.settings.patternAnchor.toString()
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("예약 실행")
        ToggleRow("예약 자동실행", state.settings.masterEnabled, { if (!state.busy || !it) onAction(UiAction.SaveSettings(state.settings.copy(masterEnabled = it))) }, "자동·수동 계획의 예약 실행", !state.busy || state.settings.masterEnabled)
        ToggleRow("주간근무일 자동예약", state.settings.dayAutoEnabled, { if (!state.busy || !it) onAction(UiAction.SaveSettings(state.settings.copy(dayAutoEnabled = it))) }, "자동 계획에만 적용", !state.busy || state.settings.dayAutoEnabled)
        Text("끄기·신청 제외는 미래 실행과 아직 전송하지 않은 신청을 멈춥니다. 이미 전송한 사이트 주문은 취소되지 않습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when { !state.settings.masterEnabled -> "예약 실행 꺼짐"; state.settings.liveScope == LiveScope.NONE -> "켜짐 · 실제 구매 승인 전 실행 차단"; state.environment.any { it.blocking } -> "켜짐 · 실행환경 차단"; else -> "켜짐 · ${if (state.settings.liveScope == LiveScope.RECURRING) "반복 실제구매" else "한 날짜 실제구매"}" }, color = MaterialTheme.colorScheme.primary)
        state.settings.liveBlockedReason?.let {
            Text("실행 차단: $it", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { reviewBlock = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("차단 해제 검토") }
        }
        OutlinedTextField(testDate, { testDate = it }, label = { Text("신청 기준 수령일 (YYYY-MM-DD)") }, isError = date == null, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (date != null && plan == null) {
            Text("선택 날짜의 단일 실행 계획 없음", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { onDate(date) }, modifier = Modifier.fillMaxWidth()) { Text("날짜 열고 수동 계획 저장") }
        }
        if (dirty) Text("변경한 설정을 먼저 저장하세요.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { recurring = true; liveConfirm = true }, enabled = recurringPlan != null && !dirty && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("반복 실제구매 활성화 확인") }
        OutlinedButton(onClick = { recurring = false; liveConfirm = true }, enabled = plan != null && !dirty && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("선택 날짜만 실제구매 활성화") }
        state.settings.liveTestDate?.let { Text("승인 수령일: $it", style = MaterialTheme.typography.bodySmall) }
        SectionTitle("근무 · 기본값")
        ChoiceField("근무 유형", draft.shiftType, ShiftType.entries, { it.label }, { draft = draft.copy(shiftType = it, patternConfirmed = false) })
        OutlinedTextField(anchor, { anchor = it; draft = draft.copy(patternConfirmed = false) }, label = { Text("근무 기준일 (YYYY-MM-DD)") }, singleLine = true, isError = parseDate(anchor) == null, modifier = Modifier.fillMaxWidth())
        parseDate(anchor)?.let { day -> Text("기준일 근무: ${ScheduleCalculator.shiftOn(day, candidate).label} · 오늘 근무: ${ScheduleCalculator.shiftOn(today, candidate).label}", style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.patternConfirmed, { draft = draft.copy(patternConfirmed = it) }); Text("기준일과 실제 근무일을 대조했습니다.", Modifier.weight(1f)) }
        if (draft.shiftType in listOf(ShiftType.REGULAR, ShiftType.ALTERNATE_A, ShiftType.ALTERNATE_B)) {
            Text("상주 근무 요일", style = MaterialTheme.typography.labelLarge)
            DayOfWeek.entries.chunked(3).forEach { group ->
                Row(Modifier.fillMaxWidth()) { group.forEach { day -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { Checkbox(day in draft.weekdays, { enabled -> draft = draft.copy(weekdays = if (enabled) draft.weekdays + day else draft.weekdays - day) }); Text(dayName(day), style = MaterialTheme.typography.bodySmall) } }; repeat(3 - group.size) { Spacer(Modifier.weight(1f)) } }
            }
        }
        QuantityControl(quantity) { quantity = it }
        OutlinedTextField(time, { time = it }, label = { Text("기본 신청 시각 (HH:mm:ss)") }, singleLine = true, isError = parseTime(time) == null, modifier = Modifier.fillMaxWidth())
        SectionTitle("금액 · 재시도")
        ToggleRow("금액 한도 적용", draft.limitEnabled, { draft = draft.copy(limitEnabled = it) })
        NumericField("개당 허용액 P (원)", p, { p = it }, draft.limitEnabled && (p.toLongOrNull() ?: 0) <= 0)
        NumericField("1회 총액 상한 C (원)", c, { c = it }, draft.limitEnabled && (c.toLongOrNull() ?: 0) <= 0)
        Text(if (!draft.limitEnabled) "금액 상한 없음" else "${quantity.toIntOrNull() ?: 0}개 적용 상한: ${appliedLimit(candidate, quantity.toIntOrNull() ?: 0)?.let { "${it}원" } ?: "P/C 입력 필요"}", color = if (draft.limitEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        NumericField("추가 재시도 (0~10회)", retries, { retries = it }, retries.toIntOrNull() !in 0..10)
        NumericField("재시도 종료 후 간격 (1~300초)", interval, { interval = it }, interval.toIntOrNull() !in 1..300)
        Text("총 시도 상한: ${1 + (retries.toIntOrNull() ?: 0)}회 · 제출 결과가 불확실하면 조회만 수행", style = MaterialTheme.typography.bodySmall)
        SectionTitle("알림 · 경보")
        ToggleRow("준비 알림", draft.preparationAlert, { draft = draft.copy(preparationAlert = it) })
        ToggleRow("실패 미디어 경보", draft.mediaAlarmEnabled, { draft = draft.copy(mediaAlarmEnabled = it) }, if (!state.mediaSupported) "차단 · 예약 기동 오디오 지원 미확인" else null, enabled = state.mediaSupported)
        if (draft.mediaAlarmEnabled) {
            Text("음량 ${draft.mediaVolumePercent}%")
            Slider(draft.mediaVolumePercent.toFloat(), { draft = draft.copy(mediaVolumePercent = it.toInt()) }, valueRange = 0f..100f)
            Text("재생 ${draft.mediaDurationSeconds}초")
            Slider(draft.mediaDurationSeconds.toFloat(), { draft = draft.copy(mediaDurationSeconds = it.toInt()) }, valueRange = 1f..60f, steps = 58)
        }
        OutlinedButton(onClick = { onAction(UiAction.TestSound) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("실패 알림음 시험") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { onAction(UiAction.SaveSettings(candidate)) }, enabled = error == null && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("설정 저장") }
        AccountSettings(state, onAction, onSite)
        SectionTitle("실행환경")
        Text("마지막 확인: ${state.lastEnvironmentCheck}", style = MaterialTheme.typography.bodySmall)
        state.environment.forEach { environment ->
            TextButton(onClick = { onAction(UiAction.OpenEnvironment(environment.key)) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                Column(Modifier.fillMaxWidth()) { Text("${environment.title} · ${environment.status}", color = if (environment.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary); Text(environment.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(state.settings.manufacturerSettingsConfirmed, { onAction(UiAction.SaveSettings(state.settings.copy(manufacturerSettingsConfirmed = it))) }); Text("삼성 절전·자동실행 설정을 직접 확인했습니다.", Modifier.weight(1f)) }
        OutlinedButton(onClick = { onAction(UiAction.RefreshEnvironment) }, modifier = Modifier.fillMaxWidth()) { Text("실행환경 다시 확인") }
        SectionTitle("백업")
        OutlinedButton(onClick = { onAction(UiAction.ExportBackup) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("설정 백업 내보내기") }
        OutlinedButton(onClick = { onAction(UiAction.ImportBackup) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("백업 가져오기") }
        Text("계정 비밀번호는 백업에서 제외됩니다. 복원 후 실제구매 승인이 필요합니다.", style = MaterialTheme.typography.bodySmall)
    }
    val confirmationPlan = if (recurring) recurringPlan else plan
    if (liveConfirm && confirmationPlan != null) PurchaseConfirmation(state, confirmationPlan, recurring, onDismiss = { liveConfirm = false }, onConfirm = { onAction(UiAction.ArmLive(confirmationPlan.date, recurring, acceptedPriceRisk = true)); liveConfirm = false })
    if (reviewBlock) state.settings.liveBlockedReason?.let { reason ->
        BlockReviewDialog(reason, !state.busy, onDismiss = { reviewBlock = false }, onConfirm = { onAction(UiAction.ReviewBlock(approved = true)); reviewBlock = false })
    }
}

@Composable
private fun NumericField(label: String, value: String, onChange: (String) -> Unit, invalid: Boolean) { OutlinedTextField(value, { if (it.length <= 18 && it.all(Char::isDigit)) onChange(it) }, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, isError = invalid, modifier = Modifier.fillMaxWidth()) }

private fun dayName(day: DayOfWeek): String = when (day) { DayOfWeek.MONDAY -> "월"; DayOfWeek.TUESDAY -> "화"; DayOfWeek.WEDNESDAY -> "수"; DayOfWeek.THURSDAY -> "목"; DayOfWeek.FRIDAY -> "금"; DayOfWeek.SATURDAY -> "토"; DayOfWeek.SUNDAY -> "일" }
