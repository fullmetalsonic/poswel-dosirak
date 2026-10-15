package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.domain.*
import java.time.LocalDate

private enum class BulkMeal(val title: String) { KEEP("기존 값 유지"), ON("켜기"), OFF("끄기") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BulkDateEditor(state: UiState, dates: Set<LocalDate>, onAction: (UiAction) -> Unit,
    onDismiss: () -> Unit, onSaved: () -> Unit) {
    val originals = remember(dates) { dates.associateWith { state.overrides[it] } }
    val initialSettings = remember(dates) { state.settings }
    var workId by remember { mutableStateOf<String?>(null) }
    var meal by remember { mutableStateOf(BulkMeal.KEEP) }
    var rememberMeal by remember { mutableStateOf(true) }
    var changeQuantity by remember { mutableStateOf(false) }
    var quantity by remember { mutableStateOf(state.settings.defaultQuantity.toString()) }
    var defaultQuantity by remember { mutableStateOf(true) }
    var changeTime by remember { mutableStateOf(false) }
    var defaultTime by remember { mutableStateOf(true) }
    var time by remember { mutableStateOf(state.settings.orderTime.format(TimeFormat)) }
    var requestId by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val type = workId?.let { WorkTypes.definition(it, state.settings) }
    val dirty = workId != null || meal != BulkMeal.KEEP || changeQuantity || changeTime
    val valid = (type?.mealDefault != null || workId == null || meal != BulkMeal.KEEP) &&
        (!changeQuantity || defaultQuantity || quantity.toIntOrNull() in 1..5) &&
        (!changeTime || defaultTime || parseTime(time)?.let(::validOrderTime) == true)
    val values = dates.associateWith { date ->
        val original = originals[date]
        val existingMeal = ScheduleCalculator.planFor(date, initialSettings, original) != null
        val enabled = when (meal) { BulkMeal.KEEP -> existingMeal; BulkMeal.ON -> true; BulkMeal.OFF -> false }
        if (!dirty) original else DateOverride(date, if (enabled) DatePolicy.MANUAL else DatePolicy.EXCLUDE,
            if (changeQuantity) { if (defaultQuantity) null else quantity.toIntOrNull() } else original?.quantity,
            if (changeTime) { if (defaultTime) null else parseTime(time) } else original?.time,
            original?.reason ?: ReservationReason.NONE, workId ?: original?.workTypeId)
    }
    val enabledCount = values.count { (date, value) -> ScheduleCalculator.planFor(date, state.settings, value) != null }
    val learned = if (workId != null && type?.mealDefault == null && rememberMeal && meal != BulkMeal.KEEP)
        mapOf(workId!! to (meal == BulkMeal.ON)) else emptyMap()
    val changedValues = values.filter { (date, value) -> value != originals[date] }
    val canExcludeWhileBusy = changedValues.isNotEmpty() && changedValues.values.all { it?.policy == DatePolicy.EXCLUDE } && learned.isEmpty()
    val completedCount = dates.count { date -> state.records.any { it.date == date && it.status == ExecutionStatus.COMPLETED } }
    LaunchedEffect(state.dateSaveResult, state.busy, requestId) {
        state.dateSaveResult?.takeIf { it.requestId == requestId }?.let {
            requestId = null
            if (it.success) onSaved() else error = it.message ?: "저장하지 못했습니다. 다시 시도해 주세요."
        }
    }
    val busy = requestId != null
    val close = { if (!busy) { if (dirty) discard = true else onDismiss() } }
    val dirtyNow by rememberUpdatedState(dirty)
    val busyNow by rememberUpdatedState(busy)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = {
        if (it == SheetValue.Hidden && (dirtyNow || busyNow)) { if (!busyNow) discard = true; false } else true
    })
    ModalBottomSheet(onDismissRequest = close, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${dates.size}일 근무 변경", style = MaterialTheme.typography.titleLarge)
            Text("${dates.minOrNull()} ~ ${dates.maxOrNull()}", style = MaterialTheme.typography.bodySmall)
            WorkTypePicker(state.settings.workTypes, workId, !busy) { selected ->
                if (workId != selected.id) {
                    workId = selected.id
                    meal = when (selected.mealDefault) { true -> BulkMeal.ON; false -> BulkMeal.OFF; null -> BulkMeal.KEEP }
                }
            }
            ChoiceField("도시락 예약", meal, BulkMeal.entries, { it.title }, { meal = it })
            if (workId != null && type?.mealDefault == null) {
                if (meal == BulkMeal.KEEP) Text("이 근무의 도시락 예약 여부를 선택해 주세요.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rememberMeal, { rememberMeal = it }, enabled = !busy)
                    Text("이 근무의 기본값으로 사용", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
            ToggleRow("수량 일괄 변경", changeQuantity, { changeQuantity = it }, enabled = !busy)
            if (changeQuantity) {
                ToggleRow("기본 수량 사용", defaultQuantity, { defaultQuantity = it }, enabled = !busy)
                if (!defaultQuantity) QuantityControl(quantity) { quantity = it }
            } else Text("수량: 날짜별 값 유지", style = MaterialTheme.typography.bodySmall)
            ToggleRow("시각 일괄 변경", changeTime, { changeTime = it }, enabled = !busy)
            if (changeTime) {
                ToggleRow("기본 시각 사용", defaultTime, { defaultTime = it }, enabled = !busy)
                if (!defaultTime) OutlinedTextField(time, { time = it }, label = { Text("신청 시각 (HH:mm:ss)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
            } else Text("시각: 날짜별 값 유지", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("${dates.size}일 · 예약 켜짐 ${enabledCount}일 · 꺼짐 ${dates.size - enabledCount}일", style = MaterialTheme.typography.bodyMedium)
            if (completedCount > 0) Text("주문완료 ${completedCount}일 · 완료 주문은 유지됩니다.", style = MaterialTheme.typography.bodySmall)
            if (!state.settings.masterEnabled) Text("자동주문 꺼짐", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                if (!dirty) onDismiss() else {
                    val id = System.nanoTime(); requestId = id; error = null
                    val submitted = if (state.busy) changedValues else values
                    onAction(UiAction.SaveDateBatch(submitted, initialSettings.generation, originals.filterKeys { it in submitted }, learned, id))
                }
            }, enabled = valid && (!state.busy || canExcludeWhileBusy) && !busy, modifier = Modifier.fillMaxWidth().testTag("bulk_date_save")) { Text(if (busy) "저장 중" else "저장") }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("변경을 버릴까요?") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("변경 버리기") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("계속 편집") } })
}
