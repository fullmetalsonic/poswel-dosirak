package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.domain.*
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateEditor(state: UiState, date: LocalDate, onAction: (UiAction) -> Unit, onDismiss: () -> Unit, onSite: (String) -> Unit, onSettings: () -> Unit = {}) {
    val original = state.overrides[date]
    var policyName by rememberSaveable(date.toString()) { mutableStateOf((original?.policy ?: DatePolicy.AUTO).name) }
    var quantity by rememberSaveable(date.toString()) { mutableStateOf((original?.quantity ?: state.settings.defaultQuantity).toString()) }
    var useDefaultTime by rememberSaveable(date.toString()) { mutableStateOf(original?.time == null) }
    var time by rememberSaveable(date.toString()) { mutableStateOf((original?.time ?: state.settings.orderTime).format(TimeFormat)) }
    var reasonName by rememberSaveable(date.toString()) { mutableStateOf((original?.reason ?: ReservationReason.NONE).name) }
    var discard by remember { mutableStateOf(false) }
    var orderConfirm by remember { mutableStateOf(false) }
    var singleConfirm by remember { mutableStateOf(false) }
    var purchaseState by remember { mutableStateOf<UiState?>(null) }
    var purchasePlan by remember { mutableStateOf<OrderPlan?>(null) }
    var changedDuringReview by remember { mutableStateOf(false) }
    val policy = DatePolicy.valueOf(policyName)
    val reason = ReservationReason.valueOf(reasonName)
    val qty = quantity.toIntOrNull()
    val edited = DateOverride(date, policy, if (policy == DatePolicy.MANUAL) qty else null, if (policy != DatePolicy.MANUAL || useDefaultTime) null else parseTime(time), reason)
    val dirty = policy != (original?.policy ?: DatePolicy.AUTO) || reason != (original?.reason ?: ReservationReason.NONE) || (policy == DatePolicy.MANUAL && (qty != (original?.quantity ?: state.settings.defaultQuantity) || useDefaultTime != (original?.time == null) || (!useDefaultTime && parseTime(time) != original?.time)))
    val valid = policy != DatePolicy.MANUAL || (qty in 1..5 && (if (useDefaultTime) validOrderTime(state.settings.orderTime) else parseTime(time)?.let(::validOrderTime) == true))
    val records = state.records.filter { it.date == date }.sortedByDescending { it.updatedAt }
    val plan = ScheduleCalculator.planFor(date, state.settings, original)
    LaunchedEffect(state.settings.generation, state.settings.accountGeneration, plan, state.loginVerified) {
        purchaseState?.let { held ->
            if (held.settings.generation != state.settings.generation || held.settings.accountGeneration != state.settings.accountGeneration || purchasePlan != plan) {
                singleConfirm = false; orderConfirm = false; purchaseState = null; purchasePlan = null; changedDuringReview = true
            }
        }
    }
    fun openPurchase(immediate: Boolean) {
        purchaseState = state.copy(settings = state.settings.copy(weekdays = state.settings.weekdays.toSet()), overrides = state.overrides.toMap())
        purchasePlan = plan; orderConfirm = immediate; singleConfirm = !immediate; changedDuringReview = false
    }
    val readyForPurchase = state.credentialsSaved && (!state.settings.dayAutoEnabled || state.settings.patternConfirmed) && validOrderTime(state.settings.orderTime) && ReservationLimits.validate(state.settings) == null
    val submissionRecorded = records.any { it.submissionPossible || it.status == ExecutionStatus.COMPLETED || it.status == ExecutionStatus.NEEDS_CHECK || it.status == ExecutionStatus.RUNNING }
    val close = { if (dirty) discard = true else onDismiss() }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일 · ${if (state.settings.patternConfirmed) ScheduleCalculator.shiftOn(date, state.settings).label else "근무표 미설정"}", style = MaterialTheme.typography.titleLarge)
            ScheduleCalculator.holidayName(date)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                DatePolicy.entries.forEachIndexed { index, item ->
                    SegmentedButton(selected = policy == item, onClick = { policyName = item.name }, shape = SegmentedButtonDefaults.itemShape(index, DatePolicy.entries.size)) {
                        Text(when (item) { DatePolicy.AUTO -> "자동"; DatePolicy.MANUAL -> "수동"; DatePolicy.EXCLUDE -> "제외" })
                    }
                }
            }
            Text(policy.label, style = MaterialTheme.typography.bodySmall)
            if (policy != DatePolicy.EXCLUDE) {
                QuantityControl(quantity) { quantity = it; policyName = DatePolicy.MANUAL.name }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(useDefaultTime, onCheckedChange = { useDefaultTime = it; if (!it) policyName = DatePolicy.MANUAL.name })
                    Text("기본 신청 시각 ${state.settings.orderTime.format(TimeFormat)}", Modifier.weight(1f))
                }
                if (!useDefaultTime) OutlinedTextField(time, onValueChange = { time = it; policyName = DatePolicy.MANUAL.name }, label = { Text("이 날짜 시각 (HH:mm:ss)") }, singleLine = true, isError = parseTime(time)?.let { !validOrderTime(it) } ?: true, modifier = Modifier.fillMaxWidth())
                Text("예약 신청 시각 06:00:00~07:59:59", style = MaterialTheme.typography.bodySmall)
            }
            ChoiceField("사유", reason, ReservationReason.entries, { it.label }, { reasonName = it.name })
            Button(onClick = { onAction(UiAction.SaveDate(edited)); onDismiss() }, enabled = valid && (!state.busy || policy == DatePolicy.EXCLUDE), modifier = Modifier.fillMaxWidth()) { Text("이날 예약 저장") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAction(UiAction.SaveDate(DateOverride(date, DatePolicy.EXCLUDE, reason = reason))); onDismiss() }, modifier = Modifier.weight(1f)) { Text("신청 제외") }
                TextButton(onClick = { onAction(UiAction.RestoreDate(date)); onDismiss() }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("자동 설정 복원") }
            }
            Text("앱에서 제외해도 사이트 주문은 취소되지 않습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionTitle("서버 확인 결과")
            if (records.isEmpty()) Text("조회 기록 없음", style = MaterialTheme.typography.bodyMedium)
            records.forEach { record ->
                Text("${record.status.label} · ${record.quantity}개${record.amount?.let { " · ${it}원" } ?: ""}", color = if (record.status == ExecutionStatus.NEEDS_CHECK || record.status == ExecutionStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text("${record.stage} · ${record.message}", style = MaterialTheme.typography.bodySmall)
                Text("확인 ${record.updatedAt.atZone(java.time.ZoneId.of("Asia/Seoul")).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))}", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = { onAction(UiAction.RefreshOrders(date)) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("다시 조회") }
            TextButton(onClick = { onSite("/order.list.php") }, modifier = Modifier.fillMaxWidth()) { Text("포스웰 주문내역 열기") }
            if (dirty) Text("편집 내용을 저장한 뒤 시험할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            if (changedDuringReview) Text("계정 또는 예약 내용이 바뀌었습니다. 다시 확인하고 동의하세요.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { onAction(UiAction.MockOrder(date)) }, enabled = !dirty && plan != null && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("모의시험") }
            if (!submissionRecorded) {
                val future = plan != null && validOrderTime(plan.time) && java.time.LocalDateTime.of(plan.date, plan.time) > java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
                if (!readyForPurchase) OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("계정·주문 조건 설정") }
                if (plan != null && !future) Text("이 날짜의 06:00~08:00 사이 미래 신청 시각을 저장하세요.", style = MaterialTheme.typography.bodySmall)
                if (!(state.settings.masterEnabled && state.settings.liveScope == LiveScope.RECURRING)) {
                    OutlinedButton(onClick = { openPurchase(false) }, enabled = !dirty && future && readyForPurchase && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("이날만 자동주문") }
                } else Text("저장한 날짜별 예약은 현재 반복 자동주문에 포함됩니다.", style = MaterialTheme.typography.bodySmall)
            }
            val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
            if (!submissionRecorded && date == now.toLocalDate() && validOrderTime(now.toLocalTime())) Button(onClick = { openPurchase(true) }, enabled = !dirty && plan != null && readyForPurchase && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("지금 주문") }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("편집을 닫을까요?") }, text = { Text("저장하지 않은 변경 내용이 사라집니다.") }, confirmButton = { TextButton(onClick = onDismiss) { Text("변경 버리고 닫기") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("계속 편집") } })
    val held = purchaseState
    val heldPlan = purchasePlan
    val immediate = orderConfirm
    if ((orderConfirm || singleConfirm) && held != null && heldPlan != null) PurchaseConfirmation(held, heldPlan, recurring = false, immediate = immediate,
        onDismiss = { singleConfirm = false; orderConfirm = false; purchaseState = null; purchasePlan = null }, onConfirm = {
            onAction(if (immediate) UiAction.OrderNow(date, acceptedPriceRisk = true, expectedGeneration = held.settings.generation, expectedAccountGeneration = held.settings.accountGeneration)
                else UiAction.ArmLive(date, recurring = false, acceptedPriceRisk = true, expectedGeneration = held.settings.generation, expectedAccountGeneration = held.settings.accountGeneration))
            singleConfirm = false; orderConfirm = false; purchaseState = null; purchasePlan = null
        })
}
