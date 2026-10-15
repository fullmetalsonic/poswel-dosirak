package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fullmetalsonic.dosirak.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkTypeSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val settings = AppSettings()

    @Test fun newTypeIsNotSavedUntilValidExplicitSave() {
        var action: UiAction.SaveWorkTypes? = null
        compose.setContent { LunchTheme(darkTheme = false) { WorkTypeSettings(UiState(settings = settings), { action = it as UiAction.SaveWorkTypes }, {}) } }
        compose.captureWorkCalendar("work-type-manager")
        compose.onNodeWithContentDescription("근무 종류 추가").performClick()
        compose.onNodeWithTag("work_type_save").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertNull(action) }
        compose.onNodeWithText("근무 이름 (1~20자)").performScrollTo().performTextInput("특별교육")
        compose.onNodeWithText("달력 표시 (1~4자)").performScrollTo().performTextInput("특교")
        compose.onNodeWithTag("work_type_save").performScrollTo().performClick()
        compose.runOnIdle {
            val added = action!!.workTypes.single { !WorkTypes.isBuiltIn(it.id) }
            assertEquals("특별교육", added.name)
            assertEquals("특교", added.calendarLabel)
            assertNull(added.mealDefault)
            assertEquals(added.id, UUID.fromString(added.id).toString())
        }
    }
    @Test fun usedCustomTypeOffersArchiveNotDelete() {
        val type = WorkTypeDefinition(UUID.randomUUID().toString(), "시험근무", "시험", WorkColorPalette.first(), false, true, 20)
        val date = LocalDate.of(2026, 10, 17)
        compose.setContent { LunchTheme(darkTheme = false) { WorkTypeSettings(UiState(settings = settings.copy(workTypes = settings.workTypes + type),
            overrides = mapOf(date to DateOverride(date, DatePolicy.EXCLUDE, workTypeId = type.id))), {}, {}) } }
        compose.onNodeWithText("시험근무").performScrollTo().performClick()
        compose.onNodeWithContentDescription("보관").performScrollTo().assertExists()
        compose.onNodeWithText("근무 종류 삭제").assertDoesNotExist()
    }
    @Test fun archivedSelectionResolvesButIsHiddenForNewDates() {
        val archived = WorkTypeDefinition(UUID.randomUUID().toString(), "이전근무", "이전", WorkColorPalette.first(), false, true, 20, archived = true)
        compose.setContent { LunchTheme(darkTheme = false) { androidx.compose.foundation.layout.Column {
            WorkTypePicker(settings.workTypes + archived, null) {}
        } } }
        compose.onNodeWithText("이전근무").assertDoesNotExist()
    }
    @Test fun narrowLargeFontChoiceKeepsFullAccessibleName() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                LunchTheme(darkTheme = false) { Box(Modifier.width(320.dp)) { androidx.compose.foundation.layout.Column {
                    WorkTypePicker(settings.workTypes, WorkTypes.DAY_SUBSTITUTE) {}
                } } }
            }
        }
        compose.onNodeWithText("주간대근").assertExists().assertIsSelected()
        compose.onNodeWithText("야간대근").assertExists()
        listOf("주간대근", "야간대근").forEach { name ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(name, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            compose.runOnIdle { assertEquals("$name must not leave an orphan character", 1, layouts.single().lineCount) }
        }
        compose.captureWorkCalendar("work-types-narrow-large-font")
        compose.onNodeWithText("더보기").performClick()
        compose.onNodeWithText("봉사활동").assertExists()
    }
}
