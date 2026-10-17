package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.domain.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

internal fun canOfferRebook(date: LocalDate, today: LocalDate, state: UiState): Boolean =
    date == today && state.records.any { it.date == date && it.accountGeneration == state.settings.accountGeneration &&
        (it.submissionPossible || it.status in setOf(ExecutionStatus.COMPLETED, ExecutionStatus.NEEDS_CHECK, ExecutionStatus.RUNNING)) }

internal fun rebookInputError(date: LocalDate, quantity: String, time: String, now: ZonedDateTime): String? = when {
    date != now.toLocalDate() -> "오늘 날짜만 다시 예약할 수 있습니다."
    quantity.toIntOrNull() !in 1..5 -> "수량은 1~5개로 입력하세요."
    parseTime(time) == null -> "신청 시각을 HH:mm:ss로 입력하세요."
    !validOrderTime(parseTime(time)!!) -> "신청 시각은 06:00:00~07:59:59입니다."
    !parseTime(time)!!.isAfter(now.toLocalTime()) -> "현재보다 이후의 신청 시각을 선택하세요."
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateEditor(state: UiState, date: LocalDate, onAction: (UiAction) -> Unit,
    onDismiss: () -> Unit, onSite: (String) -> Unit, onSettings: () -> Unit = {},
    now: () -> ZonedDateTime = { ZonedDateTime.now(ZoneId.of("Asia/Seoul")) }) {
    val original = remember(date) { state.overrides[date] }
    val initialSettings = remember(date) { state.settings }
    val expectedGeneration = rememberSaveable(date.toString()) { state.settings.generation }
    val initialMeal = remember(date) { ScheduleCalculator.planFor(date, initialSettings, original) != null }
    val base = WorkTypes.baseOn(date, state.settings)
    val initialWork = original?.workTypeId ?: WorkTypes.baseOn(date, initialSettings).id
    var selectedId by rememberSaveable(date.toString()) { mutableStateOf(initialWork) }
    var workChanged by rememberSaveable(date.toString()) { mutableStateOf(false) }
    var meal by rememberSaveable(date.toString()) { mutableStateOf<Boolean?>(initialMeal) }
    var quantity by rememberSaveable(date.toString()) { mutableStateOf((original?.quantity ?: initialSettings.defaultQuantity).toString()) }
    var defaultQuantity by rememberSaveable(date.toString()) { mutableStateOf(original?.quantity == null) }
    var defaultTime by rememberSaveable(date.toString()) { mutableStateOf(original?.time == null) }
    var time by rememberSaveable(date.toString()) { mutableStateOf((original?.time ?: initialSettings.orderTime).format(TimeFormat)) }
    var restore by rememberSaveable(date.toString()) { mutableStateOf(false) }
    var clearWorkOverride by rememberSaveable(date.toString()) { mutableStateOf(false) }
    var rememberMeal by rememberSaveable(date.toString()) { mutableStateOf(true) }
    var advanced by rememberSaveable(date.toString()) { mutableStateOf(false) }
    var showHistory by rememberSaveable(date.toString()) { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var requestId by remember { mutableStateOf<Long?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showRebook by rememberSaveable(date.toString()) { mutableStateOf(false) }
    var rebookQuantity by rememberSaveable(date.toString()) { mutableStateOf("") }
    var rebookTime by rememberSaveable(date.toString()) { mutableStateOf("") }
    var rebookGeneration by rememberSaveable(date.toString()) { mutableStateOf(0L) }
    var rebookAccountGeneration by rememberSaveable(date.toString()) { mutableStateOf(0L) }
    var rebookRequestId by remember { mutableStateOf<Long?>(null) }
    var rebookError by remember { mutableStateOf<String?>(null) }
    var rebookNow by remember { mutableStateOf(now()) }
    val selected = WorkTypes.definition(selectedId, state.settings)
    val quantityValue = if (defaultQuantity) state.settings.defaultQuantity else quantity.toIntOrNull()
    val requestedTime = if (defaultTime) state.settings.orderTime else parseTime(time)
    val dirty = restore || clearWorkOverride || workChanged || meal != initialMeal ||
        defaultQuantity != (original?.quantity == null) || !defaultQuantity && quantity.toIntOrNull() != original?.quantity ||
        defaultTime != (original?.time == null) || !defaultTime && parseTime(time) != original?.time
    val valid = meal != null && (meal != true || quantityValue in 1..5 && requestedTime?.let(::validOrderTime) == true)
    val busy = requestId != null || rebookRequestId != null
    val editedValue = if (restore) null else DateOverride(date, if (meal == true) DatePolicy.MANUAL else DatePolicy.EXCLUDE,
        if (defaultQuantity) null else quantity.toIntOrNull(), if (defaultTime) null else parseTime(time),
        original?.reason ?: ReservationReason.NONE, if (clearWorkOverride) null else if (workChanged) selectedId else original?.workTypeId)
    val learned = if (!restore && workChanged && selected?.mealDefault == null && rememberMeal && meal != null)
        mapOf(selectedId to (meal == true)) else emptyMap()
    val canExcludeWhileBusy = dirty && editedValue?.policy == DatePolicy.EXCLUDE && learned.isEmpty()
    val presentation = reservationPresentation(date, state.settings, state.overrides[date], state.records)
    LaunchedEffect(state.dateSaveResult, requestId, state.busy) {
        val result = state.dateSaveResult?.takeIf { it.requestId == requestId }
        if (result != null) {
            requestId = null
            if (result.success) onDismiss() else saveError = result.message ?: "저장하지 못했습니다. 다시 시도해 주세요."
        }
    }
    LaunchedEffect(state.rebookResult, rebookRequestId) {
        val result = state.rebookResult?.takeIf { it.requestId == rebookRequestId }
        if (result != null) {
            rebookRequestId = null
            if (result.success) { showRebook = false; onDismiss() }
            else rebookError = result.message ?: "다시 예약하지 못했습니다."
        }
    }
    LaunchedEffect(showRebook) {
        while (showRebook) { rebookNow = now(); delay(1_000) }
    }
    val close = { if (!busy) { if (dirty) discard = true else onDismiss() } }
    val dirtyNow by rememberUpdatedState(dirty)
    val busyNow by rememberUpdatedState(busy)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = {
        if (it == SheetValue.Hidden && (dirtyNow || busyNow)) { if (!busyNow) discard = true; false } else true
    })
    ModalBottomSheet(onDismissRequest = close, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${date.monthValue}월 ${date.dayOfMonth}일 ${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}", style = MaterialTheme.typography.titleLarge)
            Text("기본 근무: ${if (state.settings.patternConfirmed) base.name else "근무표 미설정"}", style = MaterialTheme.typography.bodySmall)
            ScheduleCalculator.holidayName(date)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("근무", style = MaterialTheme.typography.titleMedium)
            WorkTypePicker(state.settings.workTypes, selectedId, !busy) { type ->
                if (type.id != selectedId) {
                    selectedId = type.id; workChanged = true; restore = false; clearWorkOverride = false
                    meal = type.mealDefault; rememberMeal = true
                }
            }
            if (selected == null) Text("근무 확인 필요", color = MaterialTheme.colorScheme.error)
            if (meal == null) {
                Text("도시락 예약", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { meal = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("예약하기") }
                    OutlinedButton(onClick = { meal = false }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("예약 안 함") }
                }
            } else ToggleRow("도시락 예약", meal == true, { meal = it; restore = false }, enabled = !busy)
            if (workChanged && selected?.mealDefault == null) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(rememberMeal, { rememberMeal = it }, enabled = !busy)
                Text("이 근무의 기본값으로 사용", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            if (meal == true) {
                QuantityControl(if (defaultQuantity) state.settings.defaultQuantity.toString() else quantity) { quantity = it; defaultQuantity = false; restore = false }
                if (!defaultQuantity) TextButton(onClick = { defaultQuantity = true; restore = false }, enabled = !busy) { Text("기본 수량 사용 (${state.settings.defaultQuantity}개)") }
                TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                    Text("신청 시각 ${requestedTime?.format(TimeFormat) ?: time}", Modifier.weight(1f))
                    Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (advanced) "신청 시각 접기" else "신청 시각 펼치기")
                }
                if (advanced) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(defaultTime, { defaultTime = it; restore = false }, enabled = !busy)
                        Text("기본 시각 ${state.settings.orderTime.format(TimeFormat)}", Modifier.weight(1f))
                    }
                    if (!defaultTime) OutlinedTextField(time, { time = it; restore = false }, label = { Text("이 날짜 시각 (HH:mm:ss)") },
                        singleLine = true, enabled = !busy, isError = parseTime(time)?.let { !validOrderTime(it) } ?: true, modifier = Modifier.fillMaxWidth())
                }
            }
            if (restore) Text("기본 근무와 예약 설정을 적용합니다.", style = MaterialTheme.typography.bodySmall)
            if (presentation.orderLabel != null) Text(presentation.orderLabel, color = if (presentation.orderLabel.startsWith("주문완료")) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
            if (presentation.siteOrderMayExist && dirty) Text("근무·앞으로의 예약만 변경됩니다. 접수된 주문은 유지됩니다.", style = MaterialTheme.typography.bodySmall)
            if (!state.settings.masterEnabled) TextButton(onClick = onSettings) { Text("자동주문 꺼짐") }
            if (!valid) Text(if (meal == null) "도시락 예약 여부를 선택해 주세요." else "수량 1~5개와 신청 시각을 확인하세요.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                if (!dirty) onDismiss() else {
                    val id = System.nanoTime(); requestId = id; saveError = null
                    onAction(UiAction.SaveDateBatch(mapOf(date to editedValue), expectedGeneration, mapOf(date to original), learned, id))
                }
            }, enabled = valid && (!state.busy || canExcludeWhileBusy) && !busy, modifier = Modifier.fillMaxWidth().testTag("date_save")) { Text(if (busy) "저장 중" else "저장") }
            if (original != null || workChanged) TextButton(onClick = {
                selectedId = base.id; workChanged = false; meal = ScheduleCalculator.planFor(date, state.settings, null) != null
                defaultQuantity = true; defaultTime = true; restore = true; clearWorkOverride = true
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("원래 근무로 되돌리기") }
            TextButton(onClick = { showHistory = !showHistory }, modifier = Modifier.fillMaxWidth()) { Text("주문내역 확인") }
            if (showHistory) {
                HorizontalDivider()
                state.records.filter { it.date == date && !it.isNonSubmissionObservation() }.sortedByDescending { it.updatedAt }.forEach { record ->
                    Text("${record.status.label} · ${record.quantity}개${record.amount?.let { " · ${it}원" } ?: ""}", style = MaterialTheme.typography.bodyMedium)
                    Text(record.message, style = MaterialTheme.typography.bodySmall)
                }
                state.orderLookups[date]?.let { Text(it.message, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { onAction(UiAction.RefreshOrders(date)) }, enabled = !state.busy && !busy, modifier = Modifier.fillMaxWidth()) { Text("주문내역 조회") }
                TextButton(onClick = { onSite("/order.list.php") }, modifier = Modifier.fillMaxWidth()) { Text("포스웰 주문내역 열기") }
                if (canOfferRebook(date, now().toLocalDate(), state)) OutlinedButton(onClick = {
                    val current = now()
                    rebookNow = current
                    rebookQuantity = (quantityValue ?: state.settings.defaultQuantity).toString()
                    rebookTime = (if (current.toLocalTime() < LocalTime.of(6, 0)) LocalTime.of(6, 0)
                        else current.toLocalTime().plusMinutes(2).withNano(0)).format(TimeFormat)
                    rebookGeneration = state.settings.generation
                    rebookAccountGeneration = state.settings.accountGeneration
                    rebookError = null
                    showRebook = true
                }, enabled = !state.busy && !busy && !dirty,
                    modifier = Modifier.fillMaxWidth().testTag("rebook_open")) { Text("취소 확인 후 다시 예약") }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("변경을 버릴까요?") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("변경 버리기") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("계속 편집") } })
    if (showRebook) {
        val inputError = rebookInputError(date, rebookQuantity, rebookTime, rebookNow)
        val changed = state.settings.generation != rebookGeneration || state.settings.accountGeneration != rebookAccountGeneration
        AlertDialog(onDismissRequest = { if (!busy) showRebook = false }, title = { Text("취소 확인 후 다시 예약") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("사이트 취소가 확인된 주문만 다시 신청합니다.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(rebookQuantity, { rebookQuantity = it; rebookError = null },
                        label = { Text("수량 (1~5개)") }, singleLine = true, enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().testTag("rebook_quantity"))
                    OutlinedTextField(rebookTime, { rebookTime = it; rebookError = null },
                        label = { Text("신청 시각 (HH:mm:ss)") }, singleLine = true, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("rebook_time"))
                    val error = rebookError ?: if (changed) "설정이나 계정이 변경되었습니다. 다시 예약 화면을 다시 열어 주세요." else inputError
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }, confirmButton = {
                TextButton(onClick = {
                    val error = rebookInputError(date, rebookQuantity, rebookTime, now())
                    if (error != null) rebookError = error else if (!busy) {
                        val id = System.nanoTime()
                        rebookRequestId = id; rebookError = null
                        onAction(UiAction.ArmRebook(date, rebookQuantity.toInt(), parseTime(rebookTime)!!,
                            rebookGeneration, rebookAccountGeneration, confirmed = true, requestId = id))
                    }
                }, enabled = !busy && !state.busy && !changed && inputError == null,
                    modifier = Modifier.testTag("rebook_confirm")) { Text(if (rebookRequestId != null) "확인 중" else "다시 예약") }
            }, dismissButton = {
                TextButton(onClick = { showRebook = false }, enabled = !busy) { Text("취소") }
            })
    }
}
