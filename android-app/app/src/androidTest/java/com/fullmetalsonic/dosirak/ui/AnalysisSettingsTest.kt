package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
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
import kotlin.math.ceil
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class AnalysisSettingsTest {
    @get:Rule val compose = createComposeRule()

    private fun assertFullText(layout: TextLayoutResult, nodeWidth: Int, nodeHeight: Int) {
        val input = layout.layoutInput
        // Compose 1.7.6 fast-path semantics can retain constraints from an earlier measurement.
        val paragraph = Paragraph(text = input.text.text, style = resolveDefaults(input.style, input.layoutDirection),
            constraints = Constraints(maxWidth = nodeWidth), density = input.density,
            fontFamilyResolver = input.fontFamilyResolver, spanStyles = input.text.spanStyles,
            placeholders = input.placeholders, maxLines = input.maxLines, ellipsis = input.overflow == TextOverflow.Ellipsis)
        assertFalse("The complete text must fit its allowed line count", paragraph.didExceedMaxLines)
        assertTrue("The text must contain a rendered line", paragraph.lineCount > 0)
        repeat(paragraph.lineCount) { line ->
            assertFalse("No line may be ellipsized", paragraph.isLineEllipsized(line))
        }
        assertEquals("The last line must include the complete text", input.text.length,
            paragraph.getLineEnd(paragraph.lineCount - 1))
        assertTrue("The complete paragraph must fit the actual node height", ceil(paragraph.height).toInt() <= nodeHeight)
        repeat(input.text.length) { offset ->
            val glyph = paragraph.getBoundingBox(offset)
            assertTrue("Character $offset must fit horizontally inside its text node",
                glyph.left >= 0f && glyph.right <= nodeWidth)
            assertTrue("Character $offset must fit vertically inside its text node",
                glyph.top >= 0f && glyph.bottom <= nodeHeight)
        }
    }

    private fun show(state: UiState, actions: MutableList<UiAction> = mutableListOf()) = showState({ state }, actions)

    private fun showState(state: () -> UiState, actions: MutableList<UiAction>) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        AnalysisSettings(state(), actions::add)
                    }
                }
            }
        }
    }

    @Test fun defaultOffHasNoFalseRecordingOrExportAvailability() {
        val actions = mutableListOf<UiAction>()
        show(UiState(), actions)
        compose.onNodeWithContentDescription("상세분석 모드").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithContentDescription("분석 모드").assertIsOff().performClick()
        compose.onNodeWithTag("analysis_recording_status").assertTextEquals("기록 꺼짐")
        compose.onNodeWithTag("analysis_last_saved").assertTextEquals("마지막 기록: 없음")
        compose.onNodeWithTag("analysis_share").assertIsNotEnabled()
        compose.onNodeWithTag("analysis_export").assertIsNotEnabled()
        compose.onNodeWithTag("analysis_clear").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf(UiAction.SetAnalysisEnabled(true)), actions) }
    }

    @Test fun firstDetailEnableRequiresConfirmationAndFailedSaveRequiresItAgain() {
        val state = mutableStateOf(UiState(diagnostics = DiagnosticStatus(enabled = true)))
        val actions = mutableListOf<UiAction>()
        showState({ state.value }, actions)
        compose.onNodeWithContentDescription("상세분석 모드").performClick()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithContentDescription("상세분석 모드").assertIsOff().performClick()
        compose.onNodeWithTag("analysis_detail_confirm").performClick()
        compose.runOnIdle {
            assertEquals(listOf(UiAction.SetDetailAnalysisEnabled(true)), actions)
            state.value = state.value.copy(diagnostics = DiagnosticStatus(enabled = true, error = DiagnosticStorageError.SETTINGS_WRITE))
        }
        compose.onNodeWithContentDescription("상세분석 모드").assertIsOff().performClick()
        compose.onNodeWithTag("analysis_detail_confirm").assertExists()
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithTag("analysis_error").assertTextEquals("분석 설정을 저장하지 못했습니다.")
    }

    @Test fun acknowledgedDetailToggleAndBasicReenableRespectStoredState() {
        val state = mutableStateOf(UiState(diagnostics = DiagnosticStatus(enabled = true, detailEnabled = true, detailAcknowledged = true)))
        val actions = mutableListOf<UiAction>()
        showState({ state.value }, actions)
        compose.onNodeWithContentDescription("상세분석 모드").assertIsOn().performClick()
        compose.runOnIdle { assertEquals(listOf(UiAction.SetDetailAnalysisEnabled(false)), actions) }
        compose.runOnIdle { state.value = state.value.copy(diagnostics = DiagnosticStatus(detailAcknowledged = true)) }
        compose.onNodeWithContentDescription("상세분석 모드").assertIsOff().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(diagnostics = DiagnosticStatus(enabled = true, detailAcknowledged = true)) }
        compose.onNodeWithContentDescription("상세분석 모드").assertIsOff().performClick()
        compose.onNodeWithTag("analysis_detail_confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(UiAction.SetDetailAnalysisEnabled(true), actions.last()) }
    }

    @Test fun detailOnlyLogsRemainAvailableAndBothStorageAndDropsFitLargeFont() {
        show(UiState(diagnostics = DiagnosticStatus(enabled = true, detailEnabled = true,
            detailBytes = 2048, detailDropped = 7)))
        compose.onNodeWithTag("analysis_recording_status").assertTextEquals("기록 켜짐 · 상세 켜짐")
        compose.onNodeWithTag("analysis_storage").assertTextEquals("저장 용량: 기본 0 B · 상세 2 KB")
        compose.onNodeWithTag("analysis_dropped").assertTextEquals("누락된 기록: 기본 0개 · 상세 7개")
        listOf("분석 모드", "상세분석 모드").forEach { title ->
            val layouts = mutableListOf<TextLayoutResult>()
            val text = compose.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val bounds = text.fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                assertFullText(layouts.single(), bounds.width.roundToInt(), bounds.height.roundToInt())
                assertThrows(AssertionError::class.java) {
                    assertFullText(layouts.single(), 1, bounds.height.roundToInt())
                }
            }
        }
        compose.onNodeWithContentDescription("분석 모드").assertIsOn().assertIsDisplayed()
        compose.onNodeWithContentDescription("상세분석 모드").assertIsOn().assertIsDisplayed()
        listOf("analysis_recording_status", "analysis_last_saved", "analysis_storage", "analysis_dropped").forEach {
            val text = compose.onNodeWithTag(it).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
            val bounds = text.fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { assertFullText(layouts.single(), bounds.width.roundToInt(), bounds.height.roundToInt()) }
        }
        val screenshot = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "analysis-detail-on.png")
        screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        listOf("analysis_share", "analysis_export", "analysis_clear").forEach {
            compose.onNodeWithTag(it).performScrollTo().assertIsEnabled().assertIsDisplayed()
        }
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
        compose.onNodeWithTag("analysis_dropped").assertTextEquals("누락된 기록: 기본 4개 · 상세 0개")
        compose.onNodeWithContentDescription("상세분석 모드").assertIsNotEnabled()
        compose.onNodeWithTag("analysis_share").performScrollTo().assertIsNotEnabled().assertIsDisplayed()
        compose.onNodeWithTag("analysis_export").performScrollTo().assertIsNotEnabled().assertIsDisplayed()
        val screenshot = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "analysis-narrow.png")
        screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
