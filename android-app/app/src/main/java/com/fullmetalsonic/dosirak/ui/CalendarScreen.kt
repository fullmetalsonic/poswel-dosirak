package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalDensity
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
internal fun CalendarScreen(state: UiState, onSelectDate: (LocalDate) -> Unit, onSettings: () -> Unit, onAction: (UiAction) -> Unit = {}) {
    val today = todaySeoul()
    var monthValue by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    val month = YearMonth.parse(monthValue)
    var selection by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
    var bulkEditor by remember { mutableStateOf(false) }
    BackHandler(enabled = selection.isNotEmpty() && !bulkEditor) { selection = emptySet() }
    fun toggle(date: LocalDate) { selection = if (date in selection) selection - date else selection + date }
    val now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"))
    val next = ScheduleCalculator.upcomingPlans(state.settings, state.overrides, today).firstOrNull {
        java.time.LocalDateTime.of(it.date, it.time) > now
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("다음 예약", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(next?.let { "${it.date.monthValue}월 ${it.date.dayOfMonth}일 · ${it.quantity}개 · ${it.time.format(TimeFormat)}" } ?: "저장된 예약 없음", style = MaterialTheme.typography.titleMedium)
            val blocking = state.environment.firstOrNull { it.blocking }
            val readiness = when {
                !state.settings.masterEnabled -> "자동주문 꺼짐"
                state.settings.liveBlockedReason != null || blocking != null || !state.credentialsSaved ||
                    state.settings.liveScope == LiveScope.NONE || ReservationLimits.validate(state.settings) != null -> "자동주문 확인 필요"
                else -> "자동주문 켜짐"
            }
            TextButton(onClick = onSettings, contentPadding = PaddingValues(vertical = 8.dp)) { Text(readiness) }
            if (state.settings.masterEnabled) state.registrationMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
        HorizontalDivider()
        if (selection.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${selection.size}일 선택", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { selection = emptySet() }) { Text("취소") }
            Button(onClick = { bulkEditor = true }) { Text("근무 변경") }
        }
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
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { day ->
                            val date = start.plusDays((week * 7 + day).toLong())
                            CalendarDay(date, month, today, state, Modifier.weight(1f), date in selection,
                                { if (selection.isEmpty()) onSelectDate(it) else toggle(it) }, { toggle(it) })
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
    if (bulkEditor) BulkDateEditor(state, selection, onAction, onDismiss = { bulkEditor = false },
        onSaved = { bulkEditor = false; selection = emptySet() })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalendarDay(date: LocalDate, month: YearMonth, today: LocalDate, state: UiState, modifier: Modifier,
    selected: Boolean, onSelect: (LocalDate) -> Unit, onLongSelect: (LocalDate) -> Unit) {
    val inMonth = YearMonth.from(date) == month
    val holiday = ScheduleCalculator.holidayName(date)
    val work = if (state.settings.patternConfirmed || state.overrides[date]?.workTypeId != null)
        ScheduleCalculator.workOn(date, state.settings, state.overrides[date]) else null
    val presentation = reservationPresentation(date, state.settings, state.overrides[date], state.records)
    val label = presentation.calendarLabel
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val workHeight = maxOf(40f, 36 * scale).dp
    BoxWithConstraints(modifier) {
    val stackedStatus = maxWidth < 46.dp || scale > 1.15f
    val cellHeight = (if (stackedStatus && scale <= 1.15f) 116 + 120 * (scale - 1) else 112 + 120 * (scale - 1)).dp
    val visibleLabel = if (stackedStatus) calendarFoodText(label, true)
        else if (maxWidth < 64.dp) calendarFoodText(label, true).replace("\n", "").replace(" ", "") else label
    Surface(color = if (selected || date == today) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.combinedClickable(onClick = { onSelect(date) }, onLongClick = { onLongSelect(date) },
            onLongClickLabel = "여러 날짜 선택").height(cellHeight).padding(horizontal = 2.dp, vertical = if (stackedStatus) 4.dp else 6.dp).semantics(mergeDescendants = true) {
                contentDescription = "$date, ${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}, ${work?.name ?: if (state.overrides[date]?.workTypeId != null) "근무 확인 필요" else "근무표 미설정"}, $label, ${holiday.orEmpty()}${if (selected) ", 선택됨" else ""}"
            }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (stackedStatus) 1.dp else 2.dp)) {
            val dateColor = when {
                !inMonth -> MaterialTheme.colorScheme.outline
                holiday != null || date.dayOfWeek == DayOfWeek.SUNDAY -> MaterialTheme.colorScheme.error
                date.dayOfWeek == DayOfWeek.SATURDAY -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(date.dayOfMonth.toString(), color = dateColor, style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.fillMaxWidth().height(workHeight), contentAlignment = Alignment.Center) {
                if (work != null) WorkTypeBadge(work)
                else if (state.overrides[date]?.workTypeId != null) Text("?", color = MaterialTheme.colorScheme.error)
            }
            Text(visibleLabel, color = if (presentation.orderLabel != null && !label.startsWith("주문완료")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall, minLines = if (stackedStatus) 2 else 1, maxLines = if (stackedStatus) 2 else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (holiday != null) Text(if (holiday.startsWith("대체공휴일")) "대체공휴일" else holiday, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    }
}
