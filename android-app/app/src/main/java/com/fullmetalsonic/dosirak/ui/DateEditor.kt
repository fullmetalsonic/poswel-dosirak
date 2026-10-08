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
internal fun DateEditor(state: UiState, date: LocalDate, onAction: (UiAction) -> Unit, onDismiss: () -> Unit, onSite: (String) -> Unit) {
    val original = state.overrides[date]
    var policyName by rememberSaveable(date.toString()) { mutableStateOf((original?.policy ?: DatePolicy.AUTO).name) }
    var quantity by rememberSaveable(date.toString()) { mutableStateOf((original?.quantity ?: state.settings.defaultQuantity).toString()) }
    var useDefaultTime by rememberSaveable(date.toString()) { mutableStateOf(original?.time == null) }
    var time by rememberSaveable(date.toString()) { mutableStateOf((original?.time ?: state.settings.orderTime).format(TimeFormat)) }
    var reasonName by rememberSaveable(date.toString()) { mutableStateOf((original?.reason ?: ReservationReason.NONE).name) }
    var discard by remember { mutableStateOf(false) }
    var orderConfirm by remember { mutableStateOf(false) }
    val policy = DatePolicy.valueOf(policyName)
    val reason = ReservationReason.valueOf(reasonName)
    val qty = quantity.toIntOrNull()
    val edited = DateOverride(date, policy, if (policy == DatePolicy.MANUAL) qty else null, if (policy != DatePolicy.MANUAL || useDefaultTime) null else parseTime(time), reason)
    val dirty = policy != (original?.policy ?: DatePolicy.AUTO) || reason != (original?.reason ?: ReservationReason.NONE) || (policy == DatePolicy.MANUAL && (qty != (original?.quantity ?: state.settings.defaultQuantity) || useDefaultTime != (original?.time == null) || (!useDefaultTime && parseTime(time) != original?.time)))
    val valid = policy != DatePolicy.MANUAL || (qty in 1..5 && (useDefaultTime || parseTime(time) != null))
    val records = state.records.filter { it.date == date }.sortedByDescending { it.updatedAt }
    val plan = ScheduleCalculator.planFor(date, state.settings, original)
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
                if (!useDefaultTime) OutlinedTextField(time, onValueChange = { time = it; policyName = DatePolicy.MANUAL.name }, label = { Text("이 날짜 시각 (HH:mm:ss)") }, singleLine = true, isError = parseTime(time) == null, modifier = Modifier.fillMaxWidth())
            }
            ChoiceField("사유", reason, ReservationReason.entries, { it.label }, { reasonName = it.name })
            Button(onClick = { onAction(UiAction.SaveDate(edited)); onDismiss() }, enabled = valid && (!state.busy || policy == DatePolicy.EXCLUDE), modifier = Modifier.fillMaxWidth()) { Text("날짜 설정 저장") }
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
            OutlinedButton(onClick = { onAction(UiAction.MockOrder(date)) }, enabled = !dirty && plan != null && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("모의시험") }
            if (!submissionRecorded) Button(onClick = { orderConfirm = true }, enabled = !dirty && plan != null && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("실제 신청 확인") }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("편집을 닫을까요?") }, text = { Text("저장하지 않은 변경 내용이 사라집니다.") }, confirmButton = { TextButton(onClick = onDismiss) { Text("변경 버리고 닫기") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("계속 편집") } })
    if (orderConfirm && plan != null) PurchaseConfirmation(state, plan, recurring = false, immediate = true, onDismiss = { orderConfirm = false }, onConfirm = { onAction(UiAction.OrderNow(date, acceptedPriceRisk = true)); orderConfirm = false })
}
