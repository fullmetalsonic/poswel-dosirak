package com.fullmetalsonic.dosirak.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.domain.*
import java.util.UUID

private enum class WorkMeal(val title: String, val value: Boolean?) {
    ON("예약 켜짐", true), OFF("예약 꺼짐", false), ASK("처음 지정할 때 선택", null)
}

@Composable
internal fun WorkTypeSettings(state: UiState, onAction: (UiAction) -> Unit, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<WorkTypeDefinition?>(null) }
    BackHandler(enabled = editing == null) { onBack() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "근무일 설정으로") }
            Text("근무 종류 관리", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = {
                editing = WorkTypeDefinition(UUID.randomUUID().toString(), "", "", WorkColorPalette.first(), null, false,
                    (state.settings.workTypes.maxOfOrNull { it.sortOrder } ?: -1) + 1)
            }, enabled = !state.busy && state.settings.workTypes.count { !WorkTypes.isBuiltIn(it.id) } < WorkTypes.MAX_CUSTOM_TYPES) {
                Icon(Icons.Outlined.Add, "근무 종류 추가")
            }
        }
        state.settings.workTypes.sortedBy { it.sortOrder }.forEach { type ->
            Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { editing = type }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(20.dp).background(Color(type.color), CircleShape))
                Column(Modifier.weight(1f)) {
                    Text(type.name + if (type.archived) " · 보관됨" else "", style = MaterialTheme.typography.titleMedium)
                    Text("${type.calendarLabel} · 선택 시 ${WorkMeal.entries.first { it.value == type.mealDefault }.title}", style = MaterialTheme.typography.bodySmall)
                }
                if (type.favorite) Text("자주 사용", style = MaterialTheme.typography.labelSmall)
            }
            HorizontalDivider()
        }
    }
    editing?.let { type -> key(type.id) { WorkTypeEditor(state, type, onAction) { editing = null } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkTypeEditor(state: UiState, original: WorkTypeDefinition, onAction: (UiAction) -> Unit, onDismiss: () -> Unit) {
    val generation = remember { state.settings.generation }
    val initialTypes = remember { state.settings.workTypes }
    var name by remember { mutableStateOf(original.name) }
    var label by remember { mutableStateOf(original.calendarLabel) }
    var color by remember { mutableLongStateOf(original.color) }
    var meal by remember { mutableStateOf(WorkMeal.entries.first { it.value == original.mealDefault }) }
    var favorite by remember { mutableStateOf(original.favorite) }
    var archived by remember { mutableStateOf(original.archived) }
    var requestId by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val draft = original.copy(name = name.trim(), calendarLabel = label.trim(), color = color, mealDefault = meal.value, favorite = favorite, archived = archived)
    val proposed = if (initialTypes.any { it.id == original.id }) initialTypes.map { if (it.id == original.id) draft else it } else initialTypes + draft
    val validation = WorkTypes.validate(proposed)
    val dirty = draft != original || initialTypes.none { it.id == original.id }
    val referenced = state.overrides.values.any { it.workTypeId == original.id }
    val busy = requestId != null
    fun save(types: List<WorkTypeDefinition>) {
        val id = System.nanoTime(); requestId = id; error = null
        onAction(UiAction.SaveWorkTypes(types, generation, id))
    }
    LaunchedEffect(state.workTypesSaveResult, state.busy, requestId) {
        state.workTypesSaveResult?.takeIf { it.requestId == requestId && !state.busy }?.let {
            requestId = null
            if (it.success) onDismiss() else error = it.message ?: "저장하지 못했습니다. 다시 시도해 주세요."
        }
    }
    val close = { if (!busy) { if (dirty) discard = true else onDismiss() } }
    val dirtyNow by rememberUpdatedState(dirty)
    val busyNow by rememberUpdatedState(busy)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = {
        if (it == SheetValue.Hidden && (dirtyNow || busyNow)) { if (!busyNow) discard = true; false } else true
    })
    ModalBottomSheet(onDismissRequest = close, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (initialTypes.any { it.id == original.id }) "근무 종류 편집" else "근무 종류 추가", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text("근무 이름 (1~20자)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            OutlinedTextField(label, { label = it }, label = { Text("달력 표시 (1~4자)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            Text("색상", style = MaterialTheme.typography.labelLarge)
            (WorkColorPalette + original.color).distinct().chunked(5).forEach { colors ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    colors.forEach { swatch -> IconButton(onClick = { color = swatch }, enabled = !busy,
                        modifier = Modifier.size(44.dp).background(Color(swatch), CircleShape).semantics { contentDescription = "색상 ${swatch.toString(16)}${if (color == swatch) " 선택됨" else ""}" }) {
                        if (color == swatch) Icon(Icons.Outlined.Check, null, tint = workTextColor(Color(swatch)))
                    } }
                }
            }
            ChoiceField("이 근무 선택 시 도시락", meal, WorkMeal.entries, { it.title }, { meal = it })
            ToggleRow("자주 사용", favorite, { favorite = it }, enabled = !busy)
            if (initialTypes.any { it.id == original.id }) ToggleRow("보관", archived, { archived = it }, enabled = !busy)
            validation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { if (!dirty) onDismiss() else save(proposed) }, enabled = !busy && !state.busy && validation == null,
                modifier = Modifier.fillMaxWidth().testTag("work_type_save")) { Text(if (busy) "저장 중" else "저장") }
            if (!WorkTypes.isBuiltIn(original.id) && initialTypes.any { it.id == original.id }) {
                if (referenced) Text("사용 중인 근무는 보관할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                else TextButton(onClick = { delete = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("근무 종류 삭제") }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("변경을 버릴까요?") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("변경 버리기") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("계속 편집") } })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("이 근무 종류를 삭제할까요?") },
        confirmButton = { TextButton(onClick = { delete = false; save(initialTypes.filterNot { it.id == original.id }) }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("취소") } })
}
