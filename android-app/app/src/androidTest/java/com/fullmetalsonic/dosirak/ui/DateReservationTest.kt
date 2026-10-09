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
import androidx.test.platform.app.InstrumentationRegistry
import android.view.KeyEvent
import com.fullmetalsonic.dosirak.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

@RunWith(AndroidJUnit4::class)
class DateReservationTest {
    @get:Rule val compose = createComposeRule()
    private val offDay = LocalDate.of(2026, 10, 17)
    private val settings = AppSettings(dayAutoEnabled = true, patternConfirmed = true, shiftType = ShiftType.REGULAR)

    private fun offDayReasonCreatesManual(reason: ReservationReason) {
        var action: UiAction? = null
        compose.setContent { MaterialTheme { DateEditor(UiState(settings = settings), offDay, { action = it }, {}, {}) } }
        compose.onNodeWithText("사유 (선택): 선택 안 함").performClick()
        compose.onNodeWithText(reason.label).performClick()
        compose.onNodeWithText("예약하기").performScrollTo().performClick()
        compose.runOnIdle {
            val saved = (action as UiAction.SaveDate).value
            assertEquals(DatePolicy.MANUAL, saved.policy)
            assertEquals(1, saved.quantity)
            assertNull(saved.time)
            assertEquals(reason, saved.reason)
            assertNotNull(ScheduleCalculator.planFor(offDay, settings, saved))
        }
    }
    @Test fun offDaySupportWithoutQuantityTouchCreatesManual() = offDayReasonCreatesManual(ReservationReason.SUPPORT)
    @Test fun offDaySubstituteWithoutQuantityTouchCreatesManual() = offDayReasonCreatesManual(ReservationReason.SUBSTITUTE)

    @Test fun plannedDateCanCancelToExcludeWhileBusy() {
        var action: UiAction? = null
        val override = DateOverride(offDay, DatePolicy.MANUAL, 2)
        compose.setContent { MaterialTheme { DateEditor(UiState(settings = settings, overrides = mapOf(offDay to override), busy = true), offDay, { action = it }, {}, {}) } }
        compose.onNodeWithText("예약 취소").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(DateOverride(offDay, DatePolicy.EXCLUDE), (action as UiAction.SaveDate).value) }
    }

    @Test fun restoringOverrideReturnsWeekdayToRosterPlan() {
        val date = LocalDate.of(2026, 10, 12)
        val state = mutableStateOf(UiState(settings = settings, overrides = mapOf(date to DateOverride(date, DatePolicy.MANUAL, 3))))
        var dismissed = false
        compose.setContent { MaterialTheme { DateEditor(state.value, date, { action ->
            assertTrue(action is UiAction.RestoreDate)
            state.value = state.value.copy(overrides = emptyMap(), settings = settings.copy(generation = 1))
        }, { dismissed = true }, {}) } }
        compose.onNodeWithText("근무표대로 되돌리기").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(dismissed)
            assertEquals(DatePolicy.AUTO, ScheduleCalculator.planFor(date, state.value.settings, null)?.source)
        }
    }

    @Test fun saveClosesOnlyAfterStoredOverrideGenerationAndIdleMatch() {
        val state = mutableStateOf(UiState(settings = settings))
        var action: UiAction? = null
        var dismissed = false
        compose.setContent { MaterialTheme { DateEditor(state.value, offDay, {
            action = it; state.value = state.value.copy(busy = true)
        }, { dismissed = true }, {}) } }
        compose.onNodeWithText("예약하기").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(dismissed)
            state.value = state.value.copy(settings = settings.copy(generation = 1),
                overrides = mapOf(offDay to (action as UiAction.SaveDate).value))
        }
        compose.runOnIdle { assertFalse(dismissed); state.value = state.value.copy(busy = false) }
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun saveFailurePreservesReasonAndQuantityAtLargeFont() {
        val state = mutableStateOf(UiState(settings = settings))
        var dismissed = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                MaterialTheme { DateEditor(state.value, offDay, { state.value = state.value.copy(busy = true) }, { dismissed = true }, {}) }
            }
        }
        compose.onNodeWithContentDescription("수량 (1~5개)").performTextReplacement("3")
        compose.onNodeWithText("사유 (선택): 선택 안 함").performClick()
        compose.onNodeWithText("지원").performClick()
        compose.onNodeWithText("예약하기").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(busy = false, message = "test failure") }
        compose.onNodeWithContentDescription("수량 (1~5개)").assertTextEquals("3")
        compose.onNodeWithText("사유 (선택): 지원").assertExists()
        compose.onNodeWithText("예약을 저장하지 못했습니다. 입력 내용은 유지됩니다. 다시 확인하고 저장하세요.").assertExists()
        compose.onNodeWithText("예약하기").assertIsEnabled()
        compose.runOnIdle { assertFalse(dismissed) }
    }

    @Test fun legacyQueryDoesNotHideReservationOrCancellationInNarrowCalendar() {
        val excluded = offDay.plusDays(1)
        val observation = ExecutionRecord(offDay, 0, ExecutionStatus.NEEDS_CHECK, "HISTORY", "query")
        val state = UiState(settings = settings, overrides = mapOf(offDay to DateOverride(offDay, DatePolicy.MANUAL, 2),
            excluded to DateOverride(excluded, DatePolicy.EXCLUDE)), records = listOf(observation, observation.copy(date = excluded, stage = "HISTORY_EMPTY")))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                MaterialTheme { Box(Modifier.width(360.dp)) { CalendarScreen(state, {}, {}) } }
            }
        }
        val distance = ChronoUnit.MONTHS.between(YearMonth.from(todaySeoul()), YearMonth.from(offDay))
        repeat(kotlin.math.abs(distance).toInt()) { compose.onNodeWithContentDescription(if (distance < 0) "이전 달" else "다음 달").performClick() }
        compose.onNode(hasContentDescription(offDay.toString(), substring = true)).assert(hasContentDescription("예약 2개", substring = true))
        compose.onNode(hasContentDescription(excluded.toString(), substring = true)).assert(hasContentDescription("예약 취소", substring = true))
        compose.onNodeWithText("주문 확인 필요").assertDoesNotExist()
        compose.onNodeWithText("자동주문 꺼짐").assertExists()
    }

    @Test fun actualUncertainOrderRemainsVisibleBesidePlan() {
        val record = ExecutionRecord(offDay, 2, ExecutionStatus.NEEDS_CHECK, "SUBMISSION_UNKNOWN", "uncertain", submissionPossible = true)
        compose.setContent { MaterialTheme { DateEditor(UiState(settings = settings,
            overrides = mapOf(offDay to DateOverride(offDay, DatePolicy.MANUAL, 2)), records = listOf(record)), offDay, {}, {}, {}) } }
        compose.onNodeWithText("예약 2개").assertExists()
        compose.onNodeWithText("주문 확인 필요").assertExists()
        compose.onNodeWithText("앱 예약을 취소해도 이미 접수된 사이트 주문은 취소되지 않습니다.").assertExists()
    }

    @Test fun invalidQuantityCannotSaveAndDailyEditorHasNoPurchaseActions() {
        compose.setContent { MaterialTheme { DateEditor(UiState(settings = settings), offDay, { fail("Invalid draft must not save") }, {}, {}) } }
        compose.onNodeWithContentDescription("수량 (1~5개)").performTextReplacement("0")
        compose.onNodeWithText("예약하기").assertIsNotEnabled()
        compose.onNodeWithText("지금 주문").assertDoesNotExist()
        compose.onNodeWithText("이날만 자동주문").assertDoesNotExist()
        compose.onNodeWithText("모의시험").assertDoesNotExist()
    }

    @Test fun dirtyCloseOffersContinueEditingWithoutDiscardingQuantity() {
        var dismissed = false
        compose.setContent { MaterialTheme { DateEditor(UiState(settings = settings), offDay, {}, { dismissed = true }, {}) } }
        compose.onNodeWithContentDescription("수량 (1~5개)").performTextReplacement("3")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        // A visible keyboard may consume the first native Back; never dismiss an already-open confirmation.
        if (compose.onAllNodesWithText("편집을 닫을까요?").fetchSemanticsNodes().isEmpty()) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        compose.onNodeWithText("편집을 닫을까요?").assertExists()
        compose.onNodeWithText("계속 편집").performClick()
        compose.onNodeWithContentDescription("수량 (1~5개)").assertTextEquals("3")
        compose.runOnIdle { assertFalse(dismissed) }
    }
}
