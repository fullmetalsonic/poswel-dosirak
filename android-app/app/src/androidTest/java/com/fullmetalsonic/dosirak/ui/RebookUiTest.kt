package com.fullmetalsonic.dosirak.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fullmetalsonic.dosirak.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class RebookUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = ZonedDateTime.parse("2026-10-17T06:30:00+09:00[Asia/Seoul]")
    private val date = now.toLocalDate()
    private val settings = AppSettings(generation = 12, accountGeneration = 4)
    private val record = ExecutionRecord(date, 1, ExecutionStatus.COMPLETED, "VERIFIED", "test", accountGeneration = 4)
    private val initial = UiState(settings = settings, records = listOf(record))

    private fun openRebook() {
        compose.onNodeWithText("주문내역 확인").performScrollTo().performClick()
        compose.onNodeWithTag("rebook_open").performScrollTo().performClick()
    }

    @Test fun openingAndCancelingNeverDispatchesAnOrderOrPlanChange() {
        val actions = mutableListOf<UiAction>()
        compose.setContent { LunchTheme(darkTheme = false) {
            DateEditor(initial, date, { actions.add(it) }, {}, {}, now = { now })
        } }
        openRebook()
        compose.onNodeWithTag("rebook_time").assertTextContains("06:32:00")
        compose.onNodeWithText("취소", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("rebook_confirm").assertDoesNotExist()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun explicitConfirmationSendsCapturedAccountGenerationAndFutureTimeOnce() {
        val state = mutableStateOf(initial)
        val actions = mutableListOf<UiAction>()
        compose.setContent { LunchTheme(darkTheme = false) {
            DateEditor(state.value, date, { actions.add(it); state.value = state.value.copy(busy = true) }, {}, {}, now = { now })
        } }
        openRebook()
        compose.onNodeWithTag("rebook_quantity").performTextReplacement("2")
        compose.onNodeWithTag("rebook_time").performTextReplacement("06:31:01")
        compose.onNodeWithTag("rebook_confirm").performClick()
        compose.onNodeWithTag("rebook_confirm").assertIsNotEnabled()
        compose.runOnIdle {
            val action = actions.single() as UiAction.ArmRebook
            assertTrue(action.confirmed)
            assertEquals(date, action.date)
            assertEquals(2, action.quantity)
            assertEquals(LocalTime.of(6, 31, 1), action.time)
            assertEquals(12L, action.expectedGeneration)
            assertEquals(4L, action.expectedAccountGeneration)
        }
    }

    @Test fun pastTimeAndInvalidQuantityCannotConfirm() {
        compose.setContent { LunchTheme(darkTheme = false) {
            DateEditor(initial, date, { fail("Invalid rebook must not dispatch") }, {}, {}, now = { now })
        } }
        openRebook()
        compose.onNodeWithTag("rebook_time").performTextReplacement("06:00:00")
        compose.onNodeWithTag("rebook_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("rebook_time").performTextReplacement("06:32:00")
        compose.onNodeWithTag("rebook_quantity").performTextReplacement("6")
        compose.onNodeWithTag("rebook_confirm").assertIsNotEnabled()
    }

    @Test fun unrelatedResultsDoNotCloseAndFailureKeepsDraftForRetry() {
        val state = mutableStateOf(initial)
        var action: UiAction.ArmRebook? = null
        var dismissed = false
        compose.setContent { LunchTheme(darkTheme = false) {
            DateEditor(state.value, date, { action = it as UiAction.ArmRebook }, { dismissed = true }, {}, now = { now })
        } }
        openRebook()
        compose.onNodeWithTag("rebook_quantity").performTextReplacement("3")
        compose.onNodeWithTag("rebook_confirm").performClick()
        compose.runOnIdle {
            state.value = state.value.copy(dateSaveResult = SettingsSaveResult(action!!.requestId, true),
                rebookResult = SettingsSaveResult(action!!.requestId - 1, true))
        }
        compose.runOnIdle { assertFalse(dismissed) }
        compose.onNodeWithTag("rebook_confirm").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(rebookResult = SettingsSaveResult(action!!.requestId, false, "취소 확인 실패")) }
        compose.onNodeWithText("취소 확인 실패").assertExists()
        compose.onNodeWithTag("rebook_quantity").assertTextContains("3")
        compose.onNodeWithTag("rebook_confirm").assertIsEnabled()
        compose.runOnIdle { assertFalse(dismissed) }
    }

    @Test fun onlyMatchingSuccessClosesAndStaleSettingsDisableConfirmation() {
        val state = mutableStateOf(initial)
        var action: UiAction.ArmRebook? = null
        var dismissed = false
        compose.setContent { LunchTheme(darkTheme = false) {
            DateEditor(state.value, date, { action = it as UiAction.ArmRebook }, { dismissed = true }, {}, now = { now })
        } }
        openRebook()
        compose.runOnIdle { state.value = state.value.copy(settings = settings.copy(generation = 13)) }
        compose.onNodeWithTag("rebook_confirm").assertIsNotEnabled()
        compose.onNodeWithText("취소", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("rebook_open").performScrollTo().performClick()
        compose.onNodeWithTag("rebook_confirm").performClick()
        compose.runOnIdle { assertEquals(13L, action!!.expectedGeneration)
            state.value = state.value.copy(rebookResult = SettingsSaveResult(action!!.requestId, true)) }
        compose.runOnIdle { assertTrue(dismissed) }
        compose.onNodeWithTag("rebook_confirm").assertDoesNotExist()
    }

    @Test fun unrelatedAccountDoesNotOfferRebook() {
        compose.setContent { LunchTheme(darkTheme = false) {
            DateEditor(initial.copy(settings = settings.copy(accountGeneration = 5)), date, {}, {}, {}, now = { now })
        } }
        compose.onNodeWithText("주문내역 확인").performScrollTo().performClick()
        compose.onNodeWithTag("rebook_open").assertDoesNotExist()
    }
}
