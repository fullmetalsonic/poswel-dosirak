package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.domain.*
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

internal val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
internal fun CalendarScreen(state: UiState, onSelectDate: (LocalDate) -> Unit, onSettings: () -> Unit) {
    val today = todaySeoul()
    var monthValue by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    val month = YearMonth.parse(monthValue)
    val next = ScheduleCalculator.upcomingPlans(state.settings, state.overrides, today).firstOrNull()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("다음 신청", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(next?.let { "${it.date.monthValue}월 ${it.date.dayOfMonth}일 · ${it.quantity}개 · ${it.time.format(TimeFormat)}" } ?: "저장된 신청 계획 없음", style = MaterialTheme.typography.titleMedium)
            val blocking = state.environment.firstOrNull { it.blocking }
            val readiness = when {
                !state.settings.patternConfirmed -> "근무표 미설정"
                !state.settings.masterEnabled -> "예약 자동실행 꺼짐"
                state.settings.liveScope == LiveScope.NONE -> "켜짐 · 실제 구매 승인 필요"
                blocking != null -> "켜짐 · 실행 차단: ${blocking.title}"
                ReservationLimits.validate(state.settings) != null -> "켜짐 · 금액 한도 설정 필요"
                else -> "켜짐 · 실행환경 ${state.lastEnvironmentCheck}"
            }
            TextButton(onClick = onSettings, contentPadding = PaddingValues(vertical = 8.dp)) { Text(readiness) }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { monthValue = month.minusMonths(1).toString() }) { Icon(Icons.Outlined.ChevronLeft, "이전 달") }
            Text("${month.year}년 ${month.monthValue}월", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { monthValue = YearMonth.from(today).toString() }) { Icon(Icons.Outlined.Today, "오늘로 이동") }
            IconButton(onClick = { monthValue = month.plusMonths(1).toString() }) { Icon(Icons.Outlined.ChevronRight, "다음 달") }
        }
        if (!ScheduleCalculator.knownHolidayYear(month.year)) Text("${month.year}년 공휴일 정보 미확인", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                    listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, name ->
                        Text(name, Modifier.weight(1f).padding(vertical = 10.dp).wrapContentWidth(), color = when (i) { 0 -> MaterialTheme.colorScheme.error; 6 -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant })
                    }
                }
                val first = month.atDay(1)
                val start = first.minusDays((first.dayOfWeek.value % 7).toLong())
                val rows = ((first.dayOfWeek.value % 7 + month.lengthOfMonth() + 6) / 7)
                repeat(rows) { week ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        repeat(7) { day ->
                            val date = start.plusDays((week * 7 + day).toLong())
                            CalendarDay(date, month, today, state, Modifier.weight(1f).fillMaxHeight(), onSelectDate)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
        }
        if (state.settings.patternConfirmed) Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("주 주간", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            Text("야 야간", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("휴 휴무", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CalendarDay(date: LocalDate, month: YearMonth, today: LocalDate, state: UiState, modifier: Modifier, onSelect: (LocalDate) -> Unit) {
    val inMonth = YearMonth.from(date) == month
    val holiday = ScheduleCalculator.holidayName(date)
    val shift = if (state.settings.patternConfirmed) ScheduleCalculator.shiftOn(date, state.settings) else null
    val plan = ScheduleCalculator.planFor(date, state.settings, state.overrides[date])
    val record = state.records.filter { it.date == date }.maxByOrNull { it.updatedAt }
    val excluded = state.overrides[date]?.policy == DatePolicy.EXCLUDE
    val label = record?.let { if (it.status == ExecutionStatus.COMPLETED) "완료 ${it.quantity}개" else it.status.label } ?: plan?.let { "예정 ${it.quantity}개" } ?: if (excluded) "제외" else ""
    val statusColor = when (record?.status) {
        ExecutionStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary
        ExecutionStatus.NEEDS_CHECK, ExecutionStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(color = if (date == today) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(Modifier.clickable { onSelect(date) }.heightIn(min = 86.dp).padding(horizontal = 4.dp, vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = "$date, ${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}, ${shift?.label ?: "근무표 미설정"}, ${holiday ?: ""}, $label" }, horizontalAlignment = Alignment.CenterHorizontally) {
            val dateColor = when {
                !inMonth -> MaterialTheme.colorScheme.outline
                holiday != null || date.dayOfWeek == DayOfWeek.SUNDAY -> MaterialTheme.colorScheme.error
                date.dayOfWeek == DayOfWeek.SATURDAY -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(date.dayOfMonth.toString(), color = dateColor, style = MaterialTheme.typography.bodyMedium)
            if (shift == Shift.DAY) {
                Surface(color = Color(0xFF00659E), shape = CircleShape) {
                    Box(Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp).padding(horizontal = 4.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
                        Text(shift.label, color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else if (shift != null) Text(shift.label, Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp).wrapContentSize(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Text(label, color = statusColor, style = MaterialTheme.typography.labelSmall, minLines = 1)
            if (holiday != null) Text(if (holiday.startsWith("대체공휴일")) "대체공휴일" else holiday, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
