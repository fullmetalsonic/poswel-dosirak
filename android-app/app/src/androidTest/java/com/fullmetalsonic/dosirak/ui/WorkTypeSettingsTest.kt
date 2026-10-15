package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
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
import com.fullmetalsonic.dosirak.data.AppStore
import com.fullmetalsonic.dosirak.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class WorkTypeSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val settings = AppSettings()

    private fun assertFullBadgeText(layout: TextLayoutResult, nodeWidth: Int, nodeHeight: Int) {
        val input = layout.layoutInput
        // Compose 1.7.6 fast-path semantics mixes an old maximum width with the actual node size.
        val paragraph = Paragraph(text = input.text.text, style = resolveDefaults(input.style, input.layoutDirection),
            constraints = Constraints(maxWidth = nodeWidth), density = input.density,
            fontFamilyResolver = input.fontFamilyResolver, spanStyles = input.text.spanStyles,
            placeholders = input.placeholders, maxLines = input.maxLines, ellipsis = input.overflow == TextOverflow.Ellipsis)
        assertFalse("The full badge text must fit its allowed line count", paragraph.didExceedMaxLines)
        assertTrue("The badge text must contain a rendered line", paragraph.lineCount > 0)
        repeat(paragraph.lineCount) { line -> assertFalse("No badge line may be ellipsized", paragraph.isLineEllipsized(line)) }
        assertEquals("The final badge line must include the complete text", input.text.length, paragraph.getLineEnd(paragraph.lineCount - 1))
        assertTrue("The full paragraph must fit the actual text-node height", ceil(paragraph.height).toInt() <= nodeHeight)
    }

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
    private fun badgesFitCircles(fontScale: Float) {
        val custom = WorkTypeDefinition(UUID.randomUUID().toString(), "사용자 지정근무", "맞춤근무", 0xFFFFFF00L, false, true, 30)
        val types = settings.workTypes.map {
            if (fontScale == 1.5f && it.id == WorkTypes.OFF) it.copy(calendarLabel = "맞춤휴무") else it
        } + custom
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                LunchTheme(darkTheme = false) {
                    Column(Modifier.width(320.dp).background(Color.White), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        types.chunked(7).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { WorkTypeBadge(it) }
                            }
                        }
                    }
                }
            }
        }
        compose.awaitWorkUiFrames()
        val measured = types.associate { type ->
            val text = compose.onNodeWithTag("calendar_work_text_${type.id}", useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            type.id to layouts.single()
        }
        types.forEach { type ->
            val badge = compose.onNodeWithTag("calendar_work_${type.id}", useUnmergedTree = true)
            badge.assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
            val image = badge.captureToImage().toPixelMap()
            val expected = if (type.id == WorkTypes.OFF) Color.White else Color(type.color)
            val actual = image[image.width / 2, 2]
            assertEquals("${type.name} must retain its stored red channel", expected.red, actual.red, 0.03f)
            assertEquals("${type.name} must retain its stored green channel", expected.green, actual.green, 0.03f)
            assertEquals("${type.name} must retain its stored blue channel", expected.blue, actual.blue, 0.03f)
            assertEquals("The badge corner must remain outside the circle", Color.White.red, image[0, 0].red, 0.03f)
            assertEquals("The badge corner must remain outside the circle", Color.White.green, image[0, 0].green, 0.03f)
            assertEquals("The badge corner must remain outside the circle", Color.White.blue, image[0, 0].blue, 0.03f)
            val text = compose.onNodeWithTag("calendar_work_text_${type.id}", useUnmergedTree = true)
            val layout = measured.getValue(type.id)
            val textBounds = text.fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                assertFullBadgeText(layout, textBounds.width.roundToInt(), textBounds.height.roundToInt())
                if (fontScale == 1.3f && type.id == WorkTypes.DAY) assertThrows(AssertionError::class.java) {
                    assertFullBadgeText(layout, 1, textBounds.height.roundToInt())
                }
            }
            if (type.id != WorkTypes.OFF) {
                val textLight = layout.layoutInput.style.color.luminance()
                val backgroundLight = Color(type.color).luminance()
                val contrast = (maxOf(textLight, backgroundLight) + 0.05f) / (minOf(textLight, backgroundLight) + 0.05f)
                assertTrue("${type.name} text contrast must be at least 4.5:1", contrast >= 4.5f)
                val diagonal = kotlin.math.hypot(textBounds.width.toDouble(), textBounds.height.toDouble())
                assertTrue("The complete ${type.name} text rectangle must fit inside the circle at font $fontScale", diagonal <= image.width + 1)
                val textCenter = textBounds.center
                val circleCenter = badge.fetchSemanticsNode().boundsInRoot.center
                assertEquals(circleCenter.x, textCenter.x, 0.5f)
                assertEquals(circleCenter.y, textCenter.y, 0.5f)
            } else {
                val offText = compose.onNodeWithTag("calendar_work_text_${type.id}", useUnmergedTree = true)
                offText.assertTextEquals(type.calendarLabel.chunked(2).joinToString("\n"))
            }
        }
        compose.captureWorkCalendar("work-badges-font-${fontScale.toString().replace('.', '-')}")
    }
    @Test fun allWorkBadgesAreExactCirclesExceptOffAtFont13() = badgesFitCircles(1.3f)
    @Test fun allWorkBadgesAreExactCirclesExceptOffAtFont15() = badgesFitCircles(1.5f)

    @Test fun savedOldEducationAndCustomColorsSurviveReopening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "work-colors-${UUID.randomUUID()}.db"
        val custom = WorkTypeDefinition(UUID.randomUUID().toString(), "사용자 근무", "맞춤", 0xFFA83A50L, false, true, 30)
        val storedTypes = settings.workTypes.map { if (it.id == WorkTypes.EDUCATION) it.copy(color = 0xFF365CB4L) else it } + custom
        try {
            AppStore(context, database).use { it.saveSettings(settings.copy(workTypes = storedTypes)) }
            AppStore(context, database).use {
                assertEquals(storedTypes, it.loadSettings().workTypes)
                assertEquals(0xFF365CB4L, WorkTypes.definition(WorkTypes.EDUCATION, it.loadSettings())!!.color)
                assertEquals(custom.color, WorkTypes.definition(custom.id, it.loadSettings())!!.color)
            }
            assertEquals(0xFF6B4E9BL, WorkTypes.definition(WorkTypes.EDUCATION, AppSettings())!!.color)
        } finally { context.deleteDatabase(database) }
    }

    @Test fun realAppCalendarShowsBadgeFamiliesAtNarrowLargeFont() {
        val month = YearMonth.from(todaySeoul())
        val overrides = settings.workTypes.mapIndexed { index, type ->
            val date = month.atDay(index + 1)
            date to DateOverride(date, if (type.mealDefault == true) DatePolicy.MANUAL else DatePolicy.EXCLUDE, workTypeId = type.id)
        }.toMap()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                Box(Modifier.width(320.dp)) { LunchApp(UiState(settings = settings.copy(patternConfirmed = true), overrides = overrides), {}) }
            }
        }
        compose.onNodeWithText("포스웰도시락예약").assertExists()
        compose.onNodeWithText("포스웰\n사이트").assertIsDisplayed()
        compose.captureWorkCalendar("calendar-circle-badges-narrow-large-font")
    }
}
