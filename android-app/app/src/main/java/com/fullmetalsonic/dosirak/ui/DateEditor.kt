package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.domain.*
import java.time.LocalDate

private data class PendingReservation(val generation: Long, val expected: DateOverride?, val priorMessage: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateEditor(state: UiState, date: LocalDate, onAction: (UiAction) -> Unit,
    onDismiss: () -> Unit, onSite: (String) -> Unit, onSettings: () -> Unit = {}) {
    val original = state.overrides[date]
    val presentation = reservationPresentation(date, state.settings, original, state.records)
    val initialQuantity = original?.quantity ?: state.settings.defaultQuantity
    val initialTime = original?.time ?: state.settings.orderTime
    var quantity by rememberSaveable(date.toString()) { mutableStateOf(initialQuantity.toString()) }
    var useDefaultTime by rememberSaveable(date.toString()) { mutableStateOf(original?.time == null) }
    var time by rememberSaveable(date.toString()) { mutableStateOf(initialTime.format(TimeFormat)) }
    var reasonName by rememberSaveable(date.toString()) { mutableStateOf((original?.reason ?: ReservationReason.NONE).name) }
    var advanced by rememberSaveable(date.toString()) { mutableStateOf(false) }
    var showHistory by rememberSaveable(date.toString()) { mutableStateOf(false) }
    var discard by remember(date) { mutableStateOf(false) }
    var pending by remember(date) { mutableStateOf<PendingReservation?>(null) }
    var sawBusy by remember(date) { mutableStateOf(false) }
    var saveError by remember(date) { mutableStateOf<String?>(null) }
    val reason = ReservationReason.valueOf(reasonName)
    val qty = quantity.toIntOrNull()
    val requestedTime = if (useDefaultTime) state.settings.orderTime else parseTime(time)
    val valid = qty in 1..5 && requestedTime?.let(::validOrderTime) == true
    val edited = DateOverride(date, DatePolicy.MANUAL, qty, if (useDefaultTime) null else parseTime(time), reason)
    val dirty = qty != initialQuantity || reason != (original?.reason ?: ReservationReason.NONE) ||
        useDefaultTime != (original?.time == null) || !useDefaultTime && parseTime(time) != initialTime
    val realRecords = state.records.filter { it.date == date && !it.isNonSubmissionObservation() }.sortedByDescending { it.updatedAt }
    val lookup = state.orderLookups[date]
    LaunchedEffect(pending, state.busy, state.settings.generation, original, state.message) {
        pending?.let { request ->
            if (!state.busy && reservationSaved(state.settings.generation, request.generation, original, request.expected)) {
                pending = null
                onDismiss()
            } else if (state.busy) sawBusy = true
            else if (sawBusy || state.message != request.priorMessage) {
                pending = null
                saveError = "예약을 저장하지 못했습니다. 입력 내용은 유지됩니다. 다시 확인하고 저장하세요."
            }
        }
    }
    fun submit(value: DateOverride?) {
        sawBusy = false
        saveError = null
        pending = PendingReservation(state.settings.generation, value, state.message)
        onAction(if (value == null) UiAction.RestoreDate(date) else UiAction.SaveDate(value))
    }
    val close = { if (pending == null) { if (dirty) discard = true else onDismiss() } }
    val dirtyNow by rememberUpdatedState(dirty)
    val pendingNow by rememberUpdatedState(pending != null)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { target ->
        if (target == SheetValue.Hidden && (dirtyNow || pendingNow)) {
            if (!pendingNow) discard = true
            false
        } else true
    })
    ModalBottomSheet(onDismissRequest = close, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${date.monthValue}월 ${date.dayOfMonth}일 · ${if (state.settings.patternConfirmed) ScheduleCalculator.shiftOn(date, state.settings).label else "근무표 미설정"}", style = MaterialTheme.typography.titleLarge)
            ScheduleCalculator.holidayName(date)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (presentation.reservationLabel.isNotEmpty()) Text(presentation.reservationLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            presentation.orderLabel?.let { Text(it, style = MaterialTheme.typography.titleMedium,
                color = if (it.startsWith("주문완료")) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error) }
            QuantityControl(quantity) { quantity = it }
            ChoiceField("사유 (선택)", reason, ReservationReason.entries, { if (it == ReservationReason.NONE) "선택 안 함" else it.label }, { reasonName = it.name })
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                Text("신청 시각 ${requestedTime?.format(TimeFormat) ?: time}", Modifier.weight(1f))
                Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = if (advanced) "신청 시각 접기" else "신청 시각 펼치기")
            }
            if (advanced) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(useDefaultTime, onCheckedChange = { useDefaultTime = it })
                    Text("기본 시각 ${state.settings.orderTime.format(TimeFormat)}", Modifier.weight(1f))
                }
                if (!useDefaultTime) OutlinedTextField(time, { time = it }, label = { Text("이 날짜 시각 (HH:mm:ss)") },
                    singleLine = true, isError = parseTime(time)?.let { !validOrderTime(it) } ?: true, modifier = Modifier.fillMaxWidth())
                Text("06:00:00~07:59:59", style = MaterialTheme.typography.bodySmall)
            }
            if (!valid) Text("수량 1~5개와 신청 시각을 확인하세요.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { submit(edited) }, enabled = valid && !state.busy && pending == null, modifier = Modifier.fillMaxWidth()) {
                Text(if (presentation.plan == null) "예약하기" else "예약 변경")
            }
            if (presentation.plan != null) OutlinedButton(onClick = {
                submit(DateOverride(date, DatePolicy.EXCLUDE, reason = reason))
            }, modifier = Modifier.fillMaxWidth()) { Text("예약 취소") }
            if (original != null) TextButton(onClick = { submit(null) }, enabled = !state.busy && pending == null,
                modifier = Modifier.fillMaxWidth()) { Text("근무표대로 되돌리기") }
            if (pending != null) Text("예약 저장 확인 중", style = MaterialTheme.typography.bodySmall)
            if (presentation.siteOrderMayExist) Text("앱 예약을 취소해도 이미 접수된 사이트 주문은 취소되지 않습니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!state.settings.masterEnabled) TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("자동주문 꺼짐") }
            TextButton(onClick = { showHistory = !showHistory }, modifier = Modifier.fillMaxWidth()) { Text("주문내역 확인") }
            if (showHistory || presentation.siteOrderMayExist || lookup != null) {
                HorizontalDivider()
                realRecords.forEach { record ->
                    Text("${record.status.label} · ${record.quantity}개${record.amount?.let { " · ${it}원" } ?: ""}", style = MaterialTheme.typography.bodyMedium)
                    Text(record.message, style = MaterialTheme.typography.bodySmall)
                }
                lookup?.let { Text(it.message, style = MaterialTheme.typography.bodySmall) }
                if (realRecords.isEmpty() && lookup == null) Text("확인한 주문내역 없음", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onAction(UiAction.RefreshOrders(date)) }, enabled = !state.busy && pending == null,
                    modifier = Modifier.fillMaxWidth()) { Text("주문내역 조회") }
                TextButton(onClick = { onSite("/order.list.php") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("포스웰 주문내역 열기") }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("편집을 닫을까요?") },
        text = { Text("저장하지 않은 변경 내용이 사라집니다.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("변경 버리고 닫기") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("계속 편집") } })
}
