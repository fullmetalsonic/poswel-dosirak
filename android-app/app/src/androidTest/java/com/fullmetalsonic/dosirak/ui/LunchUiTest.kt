package com.fullmetalsonic.dosirak.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fullmetalsonic.dosirak.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

@RunWith(AndroidJUnit4::class)
class LunchUiTest {
    @get:Rule val compose = createComposeRule()
    private val date = LocalDate.of(2026, 10, 12)
    private val settings = AppSettings(unitLimit = 5000, orderLimit = 8000)

    @Test fun livePurchaseRequiresSeparateRiskConsent() {
        var confirmed = false
        compose.setContent { MaterialTheme { PurchaseConfirmation(UiState(settings = settings), OrderPlan(date, 2, LocalTime.of(6, 0), DatePolicy.MANUAL, ReservationReason.NONE), recurring = false, onDismiss = {}, onConfirm = { confirmed = true }) } }
        compose.onNodeWithText("확정").assertIsNotEnabled()
        compose.onNodeWithText("적용 상한: 8000원").assertExists()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("확정").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(confirmed) }
    }

    @Test fun missingLimitsKeepPurchaseBlockedAfterConsent() {
        compose.setContent { MaterialTheme { PurchaseConfirmation(UiState(), OrderPlan(date, 1, LocalTime.of(6, 0), DatePolicy.MANUAL, ReservationReason.NONE), recurring = false, onDismiss = {}, onConfirm = { fail("실제 구매 확정이 허용되면 안 됩니다.") }) } }
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("확정").assertIsNotEnabled()
    }

    @Test fun firstActivationCanDirectlyApproveRecurringPurchases() {
        val selectedDate = todaySeoul().plusDays(1)
        var action: UiAction? = null
        compose.setContent { MaterialTheme { SettingsScreen(UiState(settings = settings, overrides = mapOf(selectedDate to DateOverride(selectedDate, DatePolicy.MANUAL, 1))), { action = it }, onDate = {}, onSite = {}) } }
        compose.onNodeWithText("반복 실제구매 활성화 확인").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("확정").assertIsNotEnabled()
        compose.onNode(isToggleable() and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("확정").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(UiAction.ArmLive(selectedDate, recurring = true, acceptedPriceRisk = true), action) }
    }

    @Test fun recurringUsesUpcomingPlanWhenSelectedDateIsExcluded() {
        val selectedDate = todaySeoul().plusDays(1)
        val futureDate = selectedDate.plusDays(1)
        var action: UiAction? = null
        compose.setContent { MaterialTheme { SettingsScreen(UiState(settings = settings, overrides = mapOf(selectedDate to DateOverride(selectedDate, DatePolicy.EXCLUDE), futureDate to DateOverride(futureDate, DatePolicy.MANUAL, 2))), { action = it }, onDate = {}, onSite = {}) } }
        compose.onNodeWithText("반복 실제구매 활성화 확인").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("수령일: $futureDate").assertExists()
        compose.onNode(isToggleable() and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("확정").performClick()
        compose.runOnIdle { assertEquals(UiAction.ArmLive(futureDate, recurring = true, acceptedPriceRisk = true), action) }
    }

    @Test fun removingManualPlanStoresExclusionDuringBusy() {
        var action: UiAction? = null
        compose.setContent { MaterialTheme { DateEditor(UiState(settings = settings, overrides = mapOf(date to DateOverride(date, DatePolicy.MANUAL, 2)), busy = true), date, { action = it }, onDismiss = {}, onSite = {}) } }
        compose.onNode(hasText("신청 제외") and hasClickAction()).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(DatePolicy.EXCLUDE, (action as UiAction.SaveDate).value.policy); assertEquals(date, (action as UiAction.SaveDate).value.date) }
    }

    @Test fun busyMasterCanBeTurnedOff() {
        var action: UiAction? = null
        compose.setContent { MaterialTheme { SettingsScreen(UiState(settings = settings.copy(masterEnabled = true), busy = true), { action = it }, onDate = {}, onSite = {}) } }
        compose.onNodeWithContentDescription("예약 자동실행").assertIsEnabled().performClick()
        compose.runOnIdle { assertFalse((action as UiAction.SaveSettings).settings.masterEnabled) }
    }

    @Test fun busyMasterCannotBeTurnedOn() {
        compose.setContent { MaterialTheme { SettingsScreen(UiState(settings = settings, busy = true), { fail("실행 중 OFF 상태를 켜면 안 됩니다.") }, onDate = {}, onSite = {}) } }
        compose.onNodeWithContentDescription("예약 자동실행").assertIsNotEnabled()
    }

    @Test fun narrowCalendarShowsHolidayAndEntireSaturdayColumn() {
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) { CalendarScreen(UiState(), onSelectDate = {}, onSettings = {}) } } }
        val target = YearMonth.of(2026, 10)
        val distance = ChronoUnit.MONTHS.between(YearMonth.from(todaySeoul()), target)
        repeat(kotlin.math.abs(distance).toInt()) { compose.onNodeWithContentDescription(if (distance < 0) "이전 달" else "다음 달").performClick() }
        compose.onNodeWithText("한글날", useUnmergedTree = true).assertExists()
        val sunday = compose.onNodeWithContentDescription("2026-09-27", substring = true).fetchSemanticsNode().boundsInRoot
        val saturday = compose.onNodeWithContentDescription("2026-10-03", substring = true).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertTrue("일요일 시작점이 보여야 합니다.", sunday.left >= 0f)
            assertTrue("7개 열이 모두 320dp 안에 들어가야 합니다.", saturday.right - sunday.left <= with(compose.density) { 320.dp.toPx() } + 1f)
            assertTrue(sunday.right <= saturday.left)
        }
    }

    @Test fun clearingExecutionBlockRequiresExplicitReview() {
        var action: UiAction? = null
        compose.setContent { MaterialTheme { SettingsScreen(UiState(settings = settings.copy(liveBlockedReason = "서버 금액 확인 필요")), { action = it }, onDate = {}, onSite = {}) } }
        compose.onNodeWithText("차단 해제 검토").performScrollTo().performClick()
        compose.onNodeWithText("서버 금액 확인 필요").assertExists()
        compose.onNodeWithText("확인 후 차단 해제").assertIsNotEnabled()
        compose.onNode(isToggleable() and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("확인 후 차단 해제").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(UiAction.ReviewBlock(approved = true), action) }
    }

    @Test fun shiftBadgesAppearOnlyAfterPatternConfirmation() {
        val current = mutableStateOf(UiState())
        compose.setContent { MaterialTheme { CalendarScreen(current.value, onSelectDate = {}, onSettings = {}) } }
        compose.onNodeWithText("근무표 미설정").assertExists()
        listOf("주", "야", "휴").forEach { compose.onAllNodesWithText(it, useUnmergedTree = true).assertCountEquals(0) }
        compose.runOnIdle { current.value = current.value.copy(settings = current.value.settings.copy(patternConfirmed = true)) }
        listOf("주", "야", "휴").forEach { assertTrue(compose.onAllNodesWithText(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) }
    }

    @Test fun uncertainSubmissionOffersQueryAndHidesNewPurchase() {
        val record = ExecutionRecord(date, 1, ExecutionStatus.NEEDS_CHECK, "최종 제출", "응답 확인 필요", submissionPossible = true)
        var action: UiAction? = null
        compose.setContent { MaterialTheme { DateEditor(UiState(settings = settings, overrides = mapOf(date to DateOverride(date, DatePolicy.MANUAL, 1)), records = listOf(record)), date, { action = it }, onDismiss = {}, onSite = {}) } }
        compose.onNodeWithText("실제 신청 확인").assertDoesNotExist()
        compose.onNodeWithText("다시 조회").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(UiAction.RefreshOrders(date), action) }
    }

    @Test fun automaticRestoreIsExplicitAndScopedToDate() {
        var action: UiAction? = null
        compose.setContent { MaterialTheme { DateEditor(UiState(settings = settings, overrides = mapOf(date to DateOverride(date, DatePolicy.EXCLUDE))), date, { action = it }, onDismiss = {}, onSite = {}) } }
        compose.onNodeWithText("자동 설정 복원").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(UiAction.RestoreDate(date), action) }
    }
}
