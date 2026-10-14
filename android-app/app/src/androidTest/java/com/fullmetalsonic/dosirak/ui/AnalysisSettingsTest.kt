package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import com.fullmetalsonic.dosirak.diagnostics.DiagnosticStatus
import com.fullmetalsonic.dosirak.diagnostics.DiagnosticStorageError
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisSettingsTest {
    @get:Rule val compose = createComposeRule()

    private fun show(state: UiState, actions: MutableList<UiAction> = mutableListOf()) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        AnalysisSettings(state, actions::add)
                    }
                }
            }
        }
    }

    @Test fun defaultOffHasNoFalseRecordingOrExportAvailability() {
        val actions = mutableListOf<UiAction>()
        show(UiState(), actions)
        compose.onNodeWithContentDescription("분석 모드").assertIsOff().performClick()
        compose.onNodeWithTag("analysis_recording_status").assertTextEquals("기록 꺼짐")
        compose.onNodeWithTag("analysis_last_saved").assertTextEquals("마지막 기록: 없음")
        compose.onNodeWithTag("analysis_share").assertIsNotEnabled()
        compose.onNodeWithTag("analysis_export").assertIsNotEnabled()
        compose.onNodeWithTag("analysis_clear").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf(UiAction.SetAnalysisEnabled(true)), actions) }
    }

    @Test fun existingLogsCanBeSentWhileRecordingIsOffAndOrderIsBusy() {
        val actions = mutableListOf<UiAction>()
        show(UiState(busy = true, diagnostics = DiagnosticStatus(bytes = 2048)), actions)
        compose.onNodeWithTag("analysis_share").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("analysis_export").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(UiAction.ShareAnalysis, UiAction.ExportAnalysis), actions) }
    }

    @Test fun clearingRequiresConfirmationAndDoesNotProduceOrderActions() {
        val actions = mutableListOf<UiAction>()
        show(UiState(diagnostics = DiagnosticStatus(enabled = true, bytes = 2048)), actions)
        compose.onNodeWithTag("analysis_clear").performScrollTo().performClick()
        compose.onNodeWithText("취소").performClick()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithTag("analysis_clear").performScrollTo().performClick()
        compose.onNodeWithTag("analysis_clear_confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(UiAction.ClearAnalysis), actions) }
    }

    @Test fun busyErrorAndLastSavedRemainTruthfulAtNarrowWidth() {
        show(UiState(diagnosticActionBusy = true,
            diagnostics = DiagnosticStatus(enabled = true, lastSavedEpochMillis = 1_792_080_000_000L,
                bytes = 17, dropped = 4, error = DiagnosticStorageError.WRITE)))
        compose.onNodeWithTag("analysis_recording_status").assertTextEquals("기록에 문제가 있습니다")
        compose.onNodeWithTag("analysis_last_saved").assert(hasText("마지막 기록: ", substring = true))
        compose.onNodeWithTag("analysis_error").assertTextEquals("로그를 저장하지 못했습니다.")
        compose.onNodeWithTag("analysis_dropped").assertTextEquals("누락된 기록: 4개")
        compose.onNodeWithTag("analysis_share").performScrollTo().assertIsNotEnabled().assertIsDisplayed()
        compose.onNodeWithTag("analysis_export").performScrollTo().assertIsNotEnabled().assertIsDisplayed()
        val screenshot = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "analysis-narrow.png")
        screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
