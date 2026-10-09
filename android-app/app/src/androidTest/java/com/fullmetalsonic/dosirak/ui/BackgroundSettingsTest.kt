package com.fullmetalsonic.dosirak.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import com.fullmetalsonic.dosirak.domain.AppSettings
import com.fullmetalsonic.dosirak.domain.BackgroundCheckMode
import com.fullmetalsonic.dosirak.domain.EnvironmentStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class BackgroundSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun checkingModesDoNotEnablePurchases() {
        val draft = mutableStateOf(AppSettings())
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BackgroundSettings(UiState(), draft.value, { draft.value = it }, {})
            }
        } }
        saveFixtureImage("background-off.png")
        compose.onNodeWithContentDescription("백그라운드 자동실행 점검").performScrollTo().performClick()
        compose.onNodeWithContentDescription("백그라운드 자동실행 점검").assertIsOn()
        compose.runOnIdle { assertTrue(draft.value.backgroundCheckEnabled); assertEquals(BackgroundCheckMode.HOURLY, draft.value.backgroundCheckMode); assertFalse(draft.value.masterEnabled) }
        saveFixtureImage("background-hourly.png")
        compose.onNodeWithTag("background_DAILY", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("점검 시각 05:50:00").assertExists()
        saveFixtureImage("background-daily.png")
        compose.runOnIdle {
            assertTrue(draft.value.backgroundCheckEnabled)
            assertEquals(BackgroundCheckMode.DAILY, draft.value.backgroundCheckMode)
            assertFalse(draft.value.masterEnabled)
            assertFalse(draft.value.displayPriceRiskAccepted)
        }
        compose.onNodeWithTag("background_HOURLY", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("점검 시각 05:50:00").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(BackgroundCheckMode.HOURLY, draft.value.backgroundCheckMode)
            assertFalse(draft.value.masterEnabled); assertFalse(draft.value.displayPriceRiskAccepted)
        }
        compose.onNodeWithContentDescription("백그라운드 자동실행 점검").performScrollTo().performClick()
        compose.onNodeWithContentDescription("백그라운드 자동실행 점검").assertIsOff()
        compose.onNodeWithTag("background_DAILY", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertFalse(draft.value.backgroundCheckEnabled); assertFalse(draft.value.masterEnabled) }
    }

    @Test fun refreshOnlyRequestsEnvironmentInspection() {
        var action: UiAction? = null
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BackgroundSettings(UiState(backgroundLastCheck = "10-09 05:50:00"), AppSettings(), {}, { action = it })
            }
        } }
        compose.onNodeWithText("마지막 점검: 10-09 05:50:00").assertExists()
        compose.onNodeWithTag("background_refresh").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(UiAction.RefreshEnvironment, action) }
    }

    @Test fun manualCheckUsesItsActionAndDisplaysUpdatedTimeAndResultWhilePeriodicIsOff() {
        val initial = AppSettings(backgroundCheckEnabled = false, masterEnabled = false, generation = 4)
        val state = mutableStateOf(UiState(settings = initial))
        val actions = mutableListOf<UiAction>()
        var settingsChanges = 0
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BackgroundSettings(state.value, initial, { settingsChanges++ }, { action ->
                    actions += action
                    if (action == UiAction.CheckBackgroundNow) state.value = state.value.copy(
                        backgroundLastCheck = "10-09 11:05:37", backgroundCheckSummary = "점검 완료 · 자동주문 비활성")
                })
            }
        } }
        compose.onNodeWithText("신청 전에만 실행해 대기합니다.").assertExists()
        compose.onNodeWithTag("background_check_now").performScrollTo().performClick()
        compose.onNodeWithTag("background_last_check").assertTextEquals("마지막 점검: 10-09 11:05:37")
        compose.onNodeWithTag("background_check_summary").assertTextEquals("점검 완료 · 자동주문 비활성")
        compose.onNodeWithTag("background_next_check").assertTextEquals("다음 점검: 점검 꺼짐")
        compose.runOnIdle {
            assertEquals(listOf(UiAction.CheckBackgroundNow), actions)
            assertEquals(0, settingsChanges)
            assertEquals(initial, state.value.settings)
        }
    }

    @Test fun busyStateDisablesManualCheckAndRefresh() {
        var actions = 0
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BackgroundSettings(UiState(busy = true), AppSettings(), {}, { actions++ })
            }
        } }
        compose.onNodeWithTag("background_check_now").assertIsNotEnabled()
        compose.onNodeWithTag("background_refresh").assertIsNotEnabled()
        compose.onNodeWithTag("background_battery_settings").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun unknownPreparationNeverClaimsReadyAndOnlyRequestsInspection() {
        val state = mutableStateOf(UiState())
        val actions = mutableListOf<UiAction>()
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BackgroundSettings(state.value, AppSettings(), { fail("Inspection must not edit settings") }, { actions += it })
            }
        } }
        compose.onNodeWithTag("background_preparation_status").assertTextEquals("준비 상태 확인 중")
        compose.onNodeWithText("배터리 제한 해제 완료").assertDoesNotExist()
        compose.onNodeWithText("신청 2분 전 준비").assertDoesNotExist()
        compose.onNodeWithText("준비 상태 확인").assertExists()
        compose.onNodeWithTag("background_battery_settings").performClick()
        compose.runOnIdle { state.value = preparationState("확인하지 못함") }
        compose.onNodeWithTag("background_preparation_status").assertTextEquals("준비 상태 확인 중")
        compose.onNodeWithTag("background_battery_settings").performClick()
        compose.runOnIdle { assertEquals(listOf(UiAction.RefreshEnvironment, UiAction.RefreshEnvironment), actions) }
    }

    @Test fun confirmedExemptionOffersEarlyPreparationWithoutEditingStoredSettings() {
        val original = AppSettings(masterEnabled = true, generation = 39, accountGeneration = 7,
            backgroundCheckEnabled = true, backgroundCheckMode = BackgroundCheckMode.DAILY)
        var action: UiAction? = null
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BackgroundSettings(preparationState("확인됨").copy(settings = original), original,
                    { fail("Battery access must preserve saved settings") }, { action = it })
            }
        } }
        compose.onNodeWithTag("background_preparation_status").assertTextEquals("배터리 제한 해제 완료")
        compose.onNodeWithText("신청 2분 전 준비").assertExists()
        compose.onNodeWithText("배터리 제한 해제 필요").assertDoesNotExist()
        compose.onNodeWithText("상태 다시 확인").assertExists()
        compose.onNodeWithTag("background_battery_settings").performClick()
        compose.runOnIdle { assertEquals(UiAction.RefreshEnvironment, action) }
        compose.onNodeWithContentDescription("백그라운드 자동실행 점검").assertIsOn()
        compose.onNodeWithText("점검 시각 05:50:00").assertExists()
    }

    @Test fun nonExemptPreparationOffersBatteryAccessAndWaitsForActualRefresh() {
        val state = mutableStateOf(preparationState("주의"))
        val actions = mutableListOf<UiAction>()
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                BackgroundSettings(state.value, AppSettings(), { fail("Battery access must not enable automatic ordering") }, { actions += it })
            }
        } }
        compose.onNodeWithTag("background_preparation_status").assertTextEquals("배터리 제한 해제 필요")
        compose.onNodeWithText("배터리 제한 해제 완료").assertDoesNotExist()
        compose.onNodeWithText("신청 2분 전 준비").assertDoesNotExist()
        compose.onNodeWithText("배터리 제한 해제").assertExists()
        compose.onNodeWithTag("background_battery_settings").performClick()
        compose.onNodeWithTag("background_preparation_status").assertTextEquals("배터리 제한 해제 필요")
        compose.runOnIdle { state.value = preparationState("차단") }
        compose.onNodeWithTag("background_preparation_status").assertTextEquals("배터리 제한 해제 필요")
        compose.onNodeWithTag("background_battery_settings").performClick()
        compose.runOnIdle { state.value = preparationState("확인됨") }
        compose.onNodeWithTag("background_preparation_status").assertTextEquals("배터리 제한 해제 완료")
        compose.onNodeWithText("신청 2분 전 준비").assertExists()
        compose.onNodeWithText("상태 다시 확인").assertExists()
        compose.onNodeWithTag("background_battery_settings").performClick()
        compose.runOnIdle { assertEquals(listOf(UiAction.OpenEnvironment("battery"), UiAction.OpenEnvironment("battery"), UiAction.RefreshEnvironment), actions) }
    }

    @Test fun preparationControlsRemainReachableAt360WithLargeFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                    BackgroundSettings(preparationState("주의"), AppSettings(), {}, {})
                } }
            }
        }
        val button = compose.onNodeWithTag("background_battery_settings").performScrollTo().assertIsDisplayed()
        val bounds = button.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertTrue(bounds.width <= with(compose.density) { 360.dp.toPx() } + 1f)
            assertTrue(bounds.height > 0)
        }
        compose.onNodeWithTag("background_check_now").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("background_refresh").performScrollTo().assertIsDisplayed()
    }

    private fun preparationState(status: String) = UiState(environment = listOf(
        EnvironmentStatus("battery", "주문 전 사전 준비", status, "")
    ))

    private fun saveFixtureImage(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null) ?: context.cacheDir, "ui-evidence")
        directory.mkdirs()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(directory, name)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
