package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.fullmetalsonic.dosirak.domain.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

@RunWith(AndroidJUnit4::class)
class DateReservationTest {
    @get:Rule val compose = createComposeRule()
    private val offDay = LocalDate.of(2026, 10, 17)
    private val settings = AppSettings(dayAutoEnabled = true, patternConfirmed = true, shiftType = ShiftType.REGULAR)

    @Test fun daySubstituteCreatesOneAtomicInheritedReservation() {
        var action: UiAction.SaveDateBatch? = null
        compose.setContent { LunchTheme(darkTheme = false) { DateEditor(UiState(settings = settings), offDay, { action = it as UiAction.SaveDateBatch }, {}, {}) } }
        compose.onNodeWithText("주간대근").performScrollTo().performClick()
        compose.captureWorkCalendar("date-editor-day-substitute", wholeWindow = true)
        compose.performWorkSave("date_save")
        compose.runOnIdle {
            val saved = action!!.values[offDay]!!
            assertEquals(WorkTypes.DAY_SUBSTITUTE, saved.workTypeId)
            assertEquals(DatePolicy.MANUAL, saved.policy)
            assertNull(saved.quantity)
            assertNull(saved.time)
            assertEquals(mapOf(offDay to null), action!!.expectedOriginals)
        }
    }
    @Test fun untouchedDateClosesWithoutPolicyRewrite() {
        var dismissed = false
        compose.setContent { LunchTheme(darkTheme = false) { DateEditor(UiState(settings = settings), offDay, { fail("Unchanged date must not write") }, { dismissed = true }, {}) } }
        compose.performWorkSave("date_save")
        compose.runOnIdle { assertTrue(dismissed) }
    }
    @Test fun vacationProposalCanBeOverriddenAndKeepsExplicitValues() {
        val existing = DateOverride(offDay, DatePolicy.MANUAL, 2, LocalTime.of(6, 0, 5))
        var action: UiAction.SaveDateBatch? = null
        compose.setContent { LunchTheme(darkTheme = false) { DateEditor(UiState(settings = settings, overrides = mapOf(offDay to existing)), offDay,
            { action = it as UiAction.SaveDateBatch }, {}, {}) } }
        compose.onNodeWithText("휴가").performScrollTo().performClick()
        compose.onNodeWithContentDescription("도시락 예약").performScrollTo().performClick()
        compose.performWorkSave("date_save")
        compose.runOnIdle {
            val value = action!!.values[offDay]!!
            assertEquals(DatePolicy.MANUAL, value.policy)
            assertEquals(2, value.quantity)
            assertEquals(existing.time, value.time)
            assertEquals(WorkTypes.VACATION, value.workTypeId)
        }
    }
    @Test fun unknownMealMustBeChosenAndLearnedOnlyOnSave() {
        var action: UiAction.SaveDateBatch? = null
        compose.setContent { LunchTheme(darkTheme = false) { DateEditor(UiState(settings = settings), offDay, { action = it as UiAction.SaveDateBatch }, {}, {}) } }
        compose.onNodeWithText("교육").performScrollTo().performClick()
        compose.onNodeWithTag("date_save").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertNull(action) }
        compose.onNodeWithText("예약 안 함").performScrollTo().performClick()
        compose.performWorkSave("date_save")
        compose.runOnIdle {
            assertEquals(mapOf(WorkTypes.EDUCATION to false), action!!.learnedMealDefaults)
            assertEquals(DatePolicy.EXCLUDE, action!!.values[offDay]!!.policy)
        }
    }
    @Test fun restoreIsDraftUntilSave() {
        val original = DateOverride(offDay, DatePolicy.MANUAL, 3, workTypeId = WorkTypes.DAY_SUBSTITUTE)
        var action: UiAction.SaveDateBatch? = null
        compose.setContent { LunchTheme(darkTheme = false) { DateEditor(UiState(settings = settings, overrides = mapOf(offDay to original)), offDay,
            { action = it as UiAction.SaveDateBatch }, {}, {}) } }
        compose.onNodeWithText("원래 근무로 되돌리기").performScrollTo().performClick()
        compose.runOnIdle { assertNull(action) }
        compose.performWorkSave("date_save")
        compose.runOnIdle { assertTrue(action!!.values.containsKey(offDay)); assertNull(action!!.values[offDay]) }
    }
    @Test fun saveClosesOnlyForMatchingSuccessfulResult() {
        val state = mutableStateOf(UiState(settings = settings))
        var action: UiAction.SaveDateBatch? = null
        var dismissed = false
        compose.setContent { LunchTheme(darkTheme = false) { DateEditor(state.value, offDay, { action = it as UiAction.SaveDateBatch; state.value = state.value.copy(busy = true) }, { dismissed = true }, {}) } }
        compose.onNodeWithText("주간대근").performScrollTo().performClick()
        compose.performWorkSave("date_save")
        compose.runOnIdle { assertFalse(dismissed); state.value = state.value.copy(busy = false, dateSaveResult = SettingsSaveResult(action!!.requestId - 1, true)) }
        compose.runOnIdle { assertFalse(dismissed); state.value = state.value.copy(dateSaveResult = SettingsSaveResult(action!!.requestId, true)) }
        compose.runOnIdle { assertTrue(dismissed) }
    }
    @Test fun failedSaveKeepsWorkAndQuantityDraftAtLargeFont() {
        val state = mutableStateOf(UiState(settings = settings))
        var action: UiAction.SaveDateBatch? = null
        var dismissed = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                LunchTheme(darkTheme = false) { DateEditor(state.value, offDay, { action = it as UiAction.SaveDateBatch; state.value = state.value.copy(busy = true) }, { dismissed = true }, {}) }
            }
        }
        compose.onNodeWithText("주간대근").performScrollTo().performClick()
        compose.onNodeWithContentDescription("수량 (1~5개)").performScrollTo().performTextReplacement("3")
        compose.performWorkSave("date_save")
        compose.runOnIdle {
            assertNotNull("Visible enabled Save must emit the request before a simulated failure", action)
            state.value = state.value.copy(busy = false, dateSaveResult = SettingsSaveResult(action!!.requestId, false, "저장 실패"))
        }
        compose.onNodeWithContentDescription("수량 (1~5개)").assertTextEquals("3")
        compose.onNodeWithText("주간대근").assertIsSelected()
        compose.onNodeWithText("저장 실패").assertExists()
        compose.runOnIdle { assertFalse(dismissed) }
    }
    @Test fun calendarUsesActualWorkAndOnePriorityFoodState() {
        val record = ExecutionRecord(offDay, 2, ExecutionStatus.COMPLETED, "COMPLETE", "done")
        val state = UiState(settings = settings, overrides = mapOf(offDay to DateOverride(offDay, DatePolicy.MANUAL, 1, workTypeId = WorkTypes.DAY_SUBSTITUTE)), records = listOf(record))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                LunchTheme(darkTheme = false) { Box(Modifier.width(320.dp)) { CalendarScreen(state, {}, {}) } }
            }
        }
        val distance = ChronoUnit.MONTHS.between(YearMonth.from(todaySeoul()), YearMonth.from(offDay))
        repeat(kotlin.math.abs(distance).toInt()) { compose.onNodeWithContentDescription(if (distance < 0) "이전 달" else "다음 달").performClick() }
        compose.captureWorkCalendar("calendar-actual-work")
        compose.onNode(hasContentDescription(offDay.toString(), substring = true))
            .assert(hasContentDescription("주간대근", substring = true)).assert(hasContentDescription("주문완료 2개", substring = true))
            .assert(hasContentDescription("예약 1개", substring = true).not())
    }
    @Test fun actualUncertainRecordStaysVisibleAfterVacationEdit() {
        val record = ExecutionRecord(offDay, 2, ExecutionStatus.NEEDS_CHECK, "SUBMISSION_UNKNOWN", "uncertain", submissionPossible = true)
        compose.setContent { LunchTheme(darkTheme = false) { DateEditor(UiState(settings = settings, records = listOf(record)), offDay, {}, {}, {}) } }
        compose.onNodeWithText("휴가").performScrollTo().performClick()
        compose.onNodeWithText("주문 확인 필요").performScrollTo().assertExists()
        compose.onNodeWithText("근무·앞으로의 예약만 변경됩니다. 접수된 주문은 유지됩니다.").assertExists()
    }
    @Test fun invalidQuantityCannotSaveAndEditorHasNoPurchaseAction() {
        compose.setContent { LunchTheme(darkTheme = false) { DateEditor(UiState(settings = settings), offDay, { fail("Invalid draft saved") }, {}, {}) } }
        compose.onNodeWithText("주간대근").performScrollTo().performClick()
        compose.onNodeWithContentDescription("수량 (1~5개)").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("date_save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("지금 주문").assertDoesNotExist()
        compose.onNodeWithText("모의시험").assertDoesNotExist()
    }
    @Test fun bulkKeepsEachDatesQuantityAndTime() {
        val second = offDay.plusDays(1)
        val originals = mapOf(offDay to DateOverride(offDay, DatePolicy.MANUAL, 2, LocalTime.of(6, 0, 5)), second to DateOverride(second, DatePolicy.MANUAL, null))
        var action: UiAction.SaveDateBatch? = null
        compose.setContent { LunchTheme(darkTheme = false) { BulkDateEditor(UiState(settings = settings, overrides = originals), originals.keys,
            { action = it as UiAction.SaveDateBatch }, {}, {}) } }
        compose.onNodeWithText("휴가").performScrollTo().performClick()
        compose.performWorkSave("bulk_date_save")
        compose.runOnIdle {
            assertEquals(2, action!!.values[offDay]!!.quantity)
            assertEquals(originals[offDay]!!.time, action!!.values[offDay]!!.time)
            assertNull(action!!.values[second]!!.quantity)
            assertNull(action!!.values[second]!!.time)
            assertTrue(action!!.values.values.all { it!!.policy == DatePolicy.EXCLUDE && it.workTypeId == WorkTypes.VACATION })
        }
    }
    @Test fun busyVacationCanExcludeAndCloseWithoutWaitingForOrderToFinish() {
        val original = DateOverride(offDay, DatePolicy.MANUAL, 2, workTypeId = WorkTypes.DAY_SUBSTITUTE)
        val state = mutableStateOf(UiState(settings = settings, overrides = mapOf(offDay to original), busy = true))
        var action: UiAction.SaveDateBatch? = null
        var dismissed = false
        compose.setContent { LunchTheme(darkTheme = false) { DateEditor(state.value, offDay, { action = it as UiAction.SaveDateBatch }, { dismissed = true }, {}) } }
        compose.onNodeWithText("휴가").performScrollTo().performClick()
        compose.performWorkSave("date_save")
        compose.runOnIdle {
            assertEquals(DatePolicy.EXCLUDE, action!!.values[offDay]!!.policy)
            assertTrue(action!!.learnedMealDefaults.isEmpty())
            state.value = state.value.copy(dateSaveResult = SettingsSaveResult(action!!.requestId, true))
        }
        compose.runOnIdle { assertTrue(dismissed); assertTrue(state.value.busy) }
    }
    @Test fun busyLearnedDefaultCannotUseExclusionFastPath() {
        compose.setContent { LunchTheme(darkTheme = false) { DateEditor(UiState(settings = settings, busy = true), offDay, { fail("Learning while busy must not submit") }, {}, {}) } }
        compose.onNodeWithText("교육").performScrollTo().performClick()
        compose.onNodeWithText("예약 안 함").performScrollTo().performClick()
        compose.onNodeWithTag("date_save").performScrollTo().assertIsNotEnabled()
    }
    @Test fun busyBulkVacationCanExcludeButMixedKeepCannotEnableDates() {
        val second = offDay.plusDays(1)
        val originals = mapOf(offDay to DateOverride(offDay, DatePolicy.MANUAL, 2), second to DateOverride(second, DatePolicy.EXCLUDE))
        var action: UiAction.SaveDateBatch? = null
        compose.setContent { LunchTheme(darkTheme = false) { BulkDateEditor(UiState(settings = settings, overrides = originals, busy = true), originals.keys,
            { action = it as UiAction.SaveDateBatch }, {}, {}) } }
        compose.onNodeWithContentDescription("수량 일괄 변경").performScrollTo().performClick()
        compose.onNodeWithTag("bulk_date_save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("휴가").performScrollTo().performClick()
        compose.performWorkSave("bulk_date_save")
        compose.runOnIdle { assertTrue(action!!.values.values.all { it!!.policy == DatePolicy.EXCLUDE }) }
    }
    @Test fun restoreThenMealAndQuantityEditClearsOldWorkId() {
        val original = DateOverride(offDay, DatePolicy.MANUAL, 2, workTypeId = WorkTypes.DAY_SUBSTITUTE)
        var action: UiAction.SaveDateBatch? = null
        compose.setContent { LunchTheme(darkTheme = false) { DateEditor(UiState(settings = settings, overrides = mapOf(offDay to original)), offDay,
            { action = it as UiAction.SaveDateBatch }, {}, {}) } }
        compose.onNodeWithText("원래 근무로 되돌리기").performScrollTo().performClick()
        compose.onNodeWithText("휴무").assertIsSelected()
        compose.onNodeWithContentDescription("도시락 예약").performScrollTo().performClick()
        compose.onNodeWithContentDescription("수량 (1~5개)").performScrollTo().performTextReplacement("3")
        compose.onNodeWithContentDescription("수량 (1~5개)").assertTextEquals("3")
        compose.onNodeWithContentDescription("도시락 예약").assertIsOn()
        compose.onNodeWithText("휴무").assertIsSelected()
        compose.onNodeWithTag("date_save").performScrollTo().assertIsEnabled()
        compose.captureWorkCalendar("restore-edited-before-save", wholeWindow = true)
        compose.performWorkSave("date_save")
        compose.awaitWorkUiFrames()
        compose.runOnIdle {
            assertNotNull("Visible enabled Save must emit a date action", action)
            assertTrue("The action must target the edited date", action!!.values.containsKey(offDay))
            val saved = action!!.values[offDay]
            assertNotNull("Editing food and quantity after restore must save a date override, not another restore", saved)
            saved!!
            assertNull(saved.workTypeId)
            assertEquals(3, saved.quantity)
            assertEquals(DatePolicy.MANUAL, saved.policy)
            assertEquals(WorkTypes.OFF, ScheduleCalculator.workOn(offDay, settings, saved)!!.id)
        }
    }
    @Test fun originalThemeNormalPhoneCalendarCapture() {
        val today = todaySeoul()
        val upcoming = today.plusDays(1)
        val state = UiState(settings = settings, overrides = mapOf(
            today to DateOverride(today, DatePolicy.MANUAL, 2, workTypeId = WorkTypes.DAY_SUBSTITUTE),
            upcoming to DateOverride(upcoming, DatePolicy.EXCLUDE, workTypeId = WorkTypes.VACATION)),
            records = listOf(ExecutionRecord(today, 2, ExecutionStatus.COMPLETED, "COMPLETE", "synthetic")))
        compose.setContent { Box(Modifier.width(360.dp)) { LunchApp(state, {}) } }
        compose.onNodeWithText("포스웰도시락예약").assertExists()
        compose.captureWorkCalendar("calendar-original-theme-normal-phone")
    }
    @Test fun calendarLongPressSelectionOpensBulkEditorAndSavesBothDates() {
        val first = todaySeoul().plusMonths(1).withDayOfMonth(15)
        val second = first.plusDays(1)
        val originals = mapOf(first to DateOverride(first, DatePolicy.MANUAL, 2, LocalTime.of(6, 0, 5)),
            second to DateOverride(second, DatePolicy.MANUAL, 3, LocalTime.of(6, 0, 7)))
        var action: UiAction.SaveDateBatch? = null
        compose.setContent { LunchTheme(darkTheme = false) {
            Box(Modifier.width(360.dp)) {
                CalendarScreen(UiState(settings = settings, overrides = originals),
                    onSelectDate = { fail("Selection mode must not open the single-date editor") }, onSettings = {},
                    onAction = { action = it as UiAction.SaveDateBatch })
            }
        } }
        compose.onNodeWithContentDescription("다음 달").performClick()
        compose.onNode(hasContentDescription(first.toString(), substring = true)).performScrollTo()
            .performTouchInput { longClick() }
        compose.onNodeWithText("1일 선택").assertExists()
        compose.onNode(hasContentDescription(second.toString(), substring = true)).performScrollTo().performClick()
        compose.onNodeWithText("2일 선택").assertExists()
        compose.onNodeWithText("근무 변경").performScrollTo().performClick()
        compose.onNodeWithText("2일 근무 변경").assertExists()
        compose.onNodeWithText("휴가").performScrollTo().performClick()
        compose.onNodeWithText("2일 · 예약 켜짐 0일 · 꺼짐 2일").performScrollTo().assertExists()
        compose.onNodeWithTag("bulk_date_save").performScrollTo().assertIsEnabled()
        compose.awaitWorkUiFrames()
        compose.performWorkSave("bulk_date_save")
        compose.runOnIdle {
            assertNotNull(action)
            assertEquals(originals.keys, action!!.values.keys)
            originals.forEach { (date, original) ->
                val saved = action!!.values[date]!!
                assertEquals(DatePolicy.EXCLUDE, saved.policy)
                assertEquals(WorkTypes.VACATION, saved.workTypeId)
                assertEquals(original.quantity, saved.quantity)
                assertEquals(original.time, saved.time)
            }
        }
    }
}
