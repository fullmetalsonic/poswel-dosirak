package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.domain.*
import java.time.DayOfWeek

@Composable
internal fun WorkSetupScreen(settings: AppSettings, anchor: String, onAnchor: (String) -> Unit, onSettings: (AppSettings) -> Unit) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    ChoiceField("근무조", settings.shiftType, ShiftType.entries, { it.label }, { onSettings(settings.copy(shiftType = it, patternConfirmed = false)) })
    ToggleRow("주간근무일 자동예약", settings.dayAutoEnabled, { onSettings(settings.copy(dayAutoEnabled = it)) })
    if (settings.shiftType in listOf(ShiftType.REGULAR, ShiftType.ALTERNATE_A, ShiftType.ALTERNATE_B)) {
        Text("자동예약할 요일", style = MaterialTheme.typography.labelLarge)
        DayOfWeek.entries.chunked(3).forEach { days ->
            Row(Modifier.fillMaxWidth()) {
                days.forEach { day -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(day in settings.weekdays, { selected -> onSettings(settings.copy(weekdays = if (selected) settings.weekdays + day else settings.weekdays - day, patternConfirmed = false)) })
                    Text(day.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN), style = MaterialTheme.typography.bodySmall)
                } }
                repeat(3 - days.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    val today = todaySeoul()
    Text("근무표 미리보기 · ${today.monthValue}/${today.dayOfMonth}부터 14일", style = MaterialTheme.typography.titleMedium)
    repeat(2) { week ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            repeat(7) { day ->
                val date = today.plusDays((week * 7 + day).toLong())
                val shift = ScheduleCalculator.shiftOn(date, settings)
                Column(Modifier.weight(1f).fillMaxHeight().heightIn(min = 72.dp).semantics(mergeDescendants = true) { contentDescription = "미리보기 $date ${shift.label}" }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${date.monthValue}/${date.dayOfMonth}", style = MaterialTheme.typography.labelSmall)
                    if (shift == Shift.DAY) Surface(color = Color(0xFF00659E), shape = CircleShape) {
                        Box(Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp).padding(4.dp), contentAlignment = Alignment.Center) { Text(shift.label, color = Color.White, style = MaterialTheme.typography.labelLarge) }
                    } else Text(shift.label, Modifier.defaultMinSize(minHeight = 28.dp).wrapContentSize(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(settings.patternConfirmed, { onSettings(settings.copy(patternConfirmed = it)) }, modifier = Modifier.semantics { contentDescription = "실제 근무표 확인" })
        Text("위 14일의 주·야·휴가 실제 근무표와 맞습니다.", Modifier.weight(1f))
    }
    TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) { Text("고급 근무표 조정") }
    if (advanced) OutlinedTextField(anchor, onAnchor, label = { Text("근무 기준일 (YYYY-MM-DD)") }, singleLine = true, isError = parseDate(anchor) == null, modifier = Modifier.fillMaxWidth())
}
