package com.fullmetalsonic.dosirak.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
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
    private fun readyState(overrides: Map<LocalDate, DateOverride> = mapOf(todaySeoul().plusDays(1) to DateOverride(todaySeoul().plusDays(1), DatePolicy.MANUAL, 1))): UiState = UiState(
        settings = settings.copy(patternConfirmed = true, manufacturerSettingsConfirmed = true), overrides = overrides,
        credentialsSaved = true, accountLabel = "mo******", loginVerified = true,
        environment = listOf(EnvironmentStatus("exact", "정확 알람", "확인됨", "예약 허용")))
    private fun openCategory(section: SettingsSection) { compose.onNodeWithTag("settings_category_${section.name}").performScrollTo().performClick() }
    private fun openStartDialog() { compose.onNodeWithContentDescription("자동주문").performScrollTo().assertIsEnabled().performClick() }
    private fun purchaseConsent() = consentWithText("가격 변동과 한도 초과 가능성을 확인하고 실제 구매에 동의합니다.")
    private fun recurringConsent() = consentWithText("반복 실제구매와 가격 변동·한도 초과 가능성에 동의합니다.")
    private fun consentWithText(text: String) = compose.onNode(isToggleable() and (hasText(text) or hasAnyDescendant(hasText(text))))

    @Test fun registeredAlarmIsNotPresentedAsWarning() {
        val notice = "자동주문 켜짐 · 다음 신청 알람을 등록했습니다."
        compose.setContent { MaterialTheme { SettingsScreen(UiState(settings = settings.copy(masterEnabled = true),
            registrationMessage = notice), {}, onDate = {}, onSite = {}) } }
        compose.onNodeWithText(notice).assertExists()
        compose.onNodeWithText("예약 등록 확인 필요", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("settings_category_ACCOUNT").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null) ?: context.cacheDir, "ui-evidence").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(directory, "settings-categories.png")).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun livePurchaseRequiresSeparateRiskConsent() {
        var confirmed = false
        compose.setContent { MaterialTheme { PurchaseConfirmation(UiState(settings = settings), OrderPlan(date, 2, LocalTime.of(6, 0), DatePolicy.MANUAL, ReservationReason.NONE), recurring = false, onDismiss = {}, onConfirm = { confirmed = true }) } }
        compose.onNodeWithText("확정").assertIsNotEnabled()
        compose.onNodeWithText("적용 상한: 8000원").assertExists()
        purchaseConsent().performClick()
        compose.onNodeWithText("확정").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(confirmed) }
    }

    @Test fun missingLimitsKeepPurchaseBlockedAfterConsent() {
        compose.setContent { MaterialTheme { PurchaseConfirmation(UiState(), OrderPlan(date, 1, LocalTime.of(6, 0), DatePolicy.MANUAL, ReservationReason.NONE), recurring = false, onDismiss = {}, onConfirm = { fail("실제 구매 확정이 허용되면 안 됩니다.") }) } }
        purchaseConsent().performClick()
        compose.onNodeWithText("확정").assertIsNotEnabled()
    }

    @Test fun firstActivationCanDirectlyApproveRecurringPurchases() {
        val selectedDate = todaySeoul().plusDays(1)
        var action: UiAction? = null
        val state = readyState(mapOf(selectedDate to DateOverride(selectedDate, DatePolicy.MANUAL, 1)))
        compose.setContent { MaterialTheme { SettingsScreen(state, { action = it }, onDate = {}, onSite = {}) } }
        openStartDialog()
        compose.onNodeWithText("동의하고 켜기").assertIsNotEnabled()
        recurringConsent().performClick()
        compose.onNodeWithText("동의하고 켜기").assertIsEnabled().performClick()
        compose.runOnIdle { val start = action as UiAction.SaveAndArmRecurring; assertTrue(start.acceptedPriceRisk); assertFalse(start.snapshot.settings.masterEnabled); assertEquals(state.accountLabel, start.snapshot.accountLabel) }
    }

    @Test fun recurringUsesUpcomingPlanWhenSelectedDateIsExcluded() {
        val selectedDate = todaySeoul().plusDays(1)
        val futureDate = selectedDate.plusDays(1)
        var action: UiAction? = null
        val state = readyState(mapOf(selectedDate to DateOverride(selectedDate, DatePolicy.EXCLUDE), futureDate to DateOverride(futureDate, DatePolicy.MANUAL, 2)))
        compose.setContent { MaterialTheme { SettingsScreen(state, { action = it }, onDate = {}, onSite = {}) } }
        openStartDialog()
        compose.onNode(hasText("다음 계획: $futureDate · 2개 · 06:00:00") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("주간근무일 자동예약: 꺼짐 · 저장한 수동 예약만 실행") and hasAnyAncestor(isDialog())).assertExists()
        recurringConsent().performClick()
        compose.onNodeWithText("동의하고 켜기").performClick()
        compose.runOnIdle { assertFalse((action as UiAction.SaveAndArmRecurring).snapshot.settings.dayAutoEnabled) }
    }


    @Test fun busyMasterCanBeTurnedOff() {
        var action: UiAction? = null
        compose.setContent { MaterialTheme { SettingsScreen(UiState(settings = settings.copy(masterEnabled = true), busy = true), { action = it }, onDate = {}, onSite = {}) } }
        compose.onNodeWithContentDescription("자동주문").assertIsOn().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(UiAction.StopAutomatic, action) }
    }

    @Test fun busyMasterCannotBeTurnedOn() {
        compose.setContent { MaterialTheme { SettingsScreen(UiState(settings = settings, busy = true), { fail("실행 중 OFF 상태를 켜면 안 됩니다.") }, onDate = {}, onSite = {}) } }
        compose.onNodeWithContentDescription("자동주문").assertIsOff().assertIsNotEnabled()
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
        openCategory(SettingsSection.PHONE)
        compose.onNodeWithText("차단 해제 검토").performScrollTo().performClick()
        compose.onNode(hasText("주문내역의 금액을 확인해 주세요.") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithText("확인 후 차단 해제").assertIsNotEnabled()
        consentWithText("사이트 주문내역과 금액/문제를 확인했습니다.").performClick()
        compose.onNodeWithText("확인 후 차단 해제").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(UiAction.ReviewBlock(approved = true), action) }
    }

    @Test fun shiftBadgesAppearOnlyAfterPatternConfirmation() {
        val current = mutableStateOf(UiState())
        compose.setContent { MaterialTheme { CalendarScreen(current.value, onSelectDate = {}, onSettings = {}) } }
        assertTrue(compose.onAllNodesWithContentDescription("근무표 미설정", substring = true).fetchSemanticsNodes().isNotEmpty())
        listOf("주", "야", "휴").forEach { compose.onAllNodesWithText(it, useUnmergedTree = true).assertCountEquals(0) }
        compose.runOnIdle { current.value = current.value.copy(settings = current.value.settings.copy(patternConfirmed = true)) }
        listOf("주", "야", "휴").forEach { assertTrue(compose.onAllNodesWithText(it, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) }
    }



    @Test fun emptyFirstSetupJourneyEndsWithExplicitRecurringConsentOnly() {
        val current = mutableStateOf(UiState(onboardingRequired = true))
        val actions = mutableListOf<UiAction>()
        compose.setContent { MaterialTheme { SettingsScreen(current.value, { action ->
            actions += action
            when (action) {
                is UiAction.SaveCredentials -> current.value = current.value.copy(credentialsSaved = true, accountLabel = "mo******", settings = current.value.settings.copy(generation = 1, accountGeneration = 1))
                is UiAction.SaveSettings -> current.value = current.value.copy(settings = action.settings.copy(generation = 2), busy = true,
                    settingsSaveResult = SettingsSaveResult(action.requestId, true))
                UiAction.CompleteOnboarding -> current.value = current.value.copy(onboardingRequired = false)
                else -> Unit
            }
        }, onDate = {}, onSite = {}) } }
        compose.onNodeWithText("1 / 5 · 계정").assertExists()
        compose.onNode(hasSetTextAction() and hasText("아이디")).performTextReplacement("mockuser")
        compose.onNode(hasSetTextAction() and hasText("비밀번호")).performTextReplacement("mock-password")
        compose.onNodeWithText("계정 저장").performScrollTo().performClick()
        compose.onNodeWithText("다음").performScrollTo().performClick()
        compose.onNodeWithText("근무조: A조").performClick()
        compose.onNodeWithText("B조").performClick()
        compose.onNodeWithContentDescription("주간근무일 자동예약").performScrollTo().performClick()
        compose.onNodeWithContentDescription("실제 근무표 확인").performScrollTo().performClick()
        compose.onNodeWithText("다음").performScrollTo().performClick()
        compose.onNodeWithContentDescription("도시락 1개 최대금액 (원)").performScrollTo().performTextReplacement("5000")
        compose.onNodeWithContentDescription("한 번에 주문할 최대금액 (원)").performScrollTo().performTextReplacement("8000")
        compose.onNodeWithText("다음").performScrollTo().performClick()
        compose.onNodeWithText("다음").performScrollTo().performClick()
        compose.onNodeWithText("설정 완료").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(actions.contains(UiAction.CompleteOnboarding)); assertFalse(actions.any { it is UiAction.SaveAndArmRecurring }); current.value = current.value.copy(busy = false) }
        compose.onNodeWithTag("settings_category_ACCOUNT").assertExists()
        compose.onNodeWithContentDescription("자동주문").assertIsOff()
        openStartDialog()
        saveDialogImage("first-setup-consent.png")
        compose.onNodeWithText("동의하고 켜기").assertIsNotEnabled()
        recurringConsent().performClick()
        compose.onNodeWithText("동의하고 켜기").performClick()
        compose.runOnIdle {
            val start = actions.last() as UiAction.SaveAndArmRecurring
            assertTrue(start.acceptedPriceRisk); assertTrue(start.snapshot.settings.dayAutoEnabled)
            assertTrue(start.snapshot.settings.patternConfirmed); assertFalse(start.snapshot.settings.masterEnabled)
            assertEquals(ShiftType.B, start.snapshot.settings.shiftType)
            assertEquals(5000L, start.snapshot.settings.unitLimit); assertEquals(8000L, start.snapshot.settings.orderLimit)
        }
    }

    @Test fun cancellingConsentDoesNotSaveOrActivate() {
        val actions = mutableListOf<UiAction>()
        compose.setContent { MaterialTheme { SettingsScreen(readyState(), { actions += it }, onDate = {}, onSite = {}) } }
        openStartDialog()
        compose.onNodeWithText("취소").performClick()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun externalStateUpdateKeepsUnsavedInputsAndSaveSynchronizesExplicitly() {
        val current = mutableStateOf(readyState())
        var saved: UiAction.SaveSettings? = null
        compose.setContent { MaterialTheme { SettingsScreen(current.value, { action ->
            if (action is UiAction.SaveSettings) { saved = action; current.value = current.value.copy(settings = action.settings.copy(generation = current.value.settings.generation + 1), message = "설정을 저장했습니다.",
                settingsSaveResult = SettingsSaveResult(action.requestId, true)) }
        }, onDate = {}, onSite = {}) } }
        openCategory(SettingsSection.ORDER)
        compose.onNodeWithContentDescription("도시락 1개 최대금액 (원)").performTextReplacement("6000")
        compose.onNodeWithContentDescription("수량 (1~5개)").performTextReplacement("3")
        compose.runOnIdle { current.value = current.value.copy(settings = current.value.settings.copy(generation = 1, accountGeneration = 1)) }
        compose.onNodeWithContentDescription("도시락 1개 최대금액 (원)").assertTextEquals("6000")
        compose.onNodeWithContentDescription("수량 (1~5개)").assertTextEquals("3")
        compose.onNodeWithText("저장").performScrollTo().performClick()
        compose.onNodeWithText("설정 저장됨").assertExists()
        compose.runOnIdle { assertEquals(6000L, saved!!.settings.unitLimit); assertEquals(3, saved!!.settings.defaultQuantity); assertEquals(1L, saved!!.expectedGeneration) }
    }

    @Test fun savingEditsWhileOnPreservesExecutionIntent() {
        val current = mutableStateOf(readyState().let { it.copy(settings = it.settings.copy(masterEnabled = true, liveScope = LiveScope.RECURRING, displayPriceRiskAccepted = true)) })
        var saved: UiAction.SaveSettings? = null
        compose.setContent { MaterialTheme { SettingsScreen(current.value, { action -> if (action is UiAction.SaveSettings) { saved = action; current.value = current.value.copy(settings = action.settings.copy(generation = 1),
            settingsSaveResult = SettingsSaveResult(action.requestId, true)) } }, onDate = {}, onSite = {}) } }
        openCategory(SettingsSection.ORDER)
        compose.onNodeWithContentDescription("도시락 1개 최대금액 (원)").performTextReplacement("6000")
        compose.onNodeWithText("저장").performScrollTo().performClick()
        compose.onNodeWithText("설정 저장됨").assertExists()
        compose.runOnIdle { assertTrue(saved!!.settings.masterEnabled); assertEquals(LiveScope.RECURRING, saved!!.settings.liveScope); assertEquals(0L, saved!!.expectedGeneration) }
    }

    @Test fun settingsGenerationChangeClosesConsentForReconfirmation() {
        val current = mutableStateOf(readyState())
        val actions = mutableListOf<UiAction>()
        compose.setContent { MaterialTheme { SettingsScreen(current.value, { actions += it }, onDate = {}, onSite = {}) } }
        openStartDialog()
        recurringConsent().performClick()
        compose.runOnIdle { current.value = current.value.copy(settings = current.value.settings.copy(generation = 1, accountGeneration = 1)) }
        compose.onNodeWithText("동의하고 켜기").assertDoesNotExist()
        compose.onNodeWithText("설정이 바뀌었습니다. 다시 확인해 주세요.").assertExists()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun missingAccountRecoveryButtonReturnsToAccountInputs() {
        compose.setContent { MaterialTheme { SettingsScreen(UiState(), {}, onDate = {}, onSite = {}) } }
        openStartDialog()
        compose.onNode(hasText("계정 저장") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("아이디")).assertExists()
        compose.onNodeWithText("처음 설정").assertDoesNotExist()
    }


    @Test fun largeFontNarrowOrderInputsAndConsentRemainReachable() {
        compose.setContent { val baseDensity = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, 2f)) { MaterialTheme { Box(Modifier.width(320.dp)) { SettingsScreen(readyState(), {}, onDate = {}, onSite = {}) } } } }
        SettingsSection.entries.forEach { compose.onNodeWithTag("settings_category_${it.name}").performScrollTo().assertExists() }
        openCategory(SettingsSection.ORDER)
        compose.onNodeWithContentDescription("도시락 1개 최대금액 (원)").performScrollTo().performClick().performTextReplacement("5000")
        val input = compose.onNodeWithContentDescription("도시락 1개 최대금액 (원)").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { assertTrue(input.width <= with(compose.density) { 320.dp.toPx() } + 1f) }
        compose.onNodeWithContentDescription("설정 목록으로").performScrollTo().performClick()
        openStartDialog()
        saveDialogImage("recurring-consent-large-font.png")
        recurringConsent().assertIsDisplayed()
        compose.onNodeWithText("동의하고 켜기").assertIsNotEnabled()
    }

    @Test fun manualOnlyLimitOffDoesNotRequireLoginOrManufacturerAcknowledgement() {
        val target = todaySeoul().plusDays(1)
        val initial = readyState(mapOf(target to DateOverride(target, DatePolicy.MANUAL, 2)))
        val current = mutableStateOf(initial.copy(settings = initial.settings.copy(dayAutoEnabled = false, patternConfirmed = false,
            manufacturerSettingsConfirmed = false, unitLimit = null, orderLimit = null), loginVerified = false))
        var action: UiAction? = null
        compose.setContent { MaterialTheme { SettingsScreen(current.value, { value ->
            action = value
            if (value is UiAction.SaveSettings) current.value = current.value.copy(settings = value.settings.copy(generation = 1),
                settingsSaveResult = SettingsSaveResult(value.requestId, true))
        }, onDate = {}, onSite = {}) } }
        openCategory(SettingsSection.ORDER)
        compose.onNodeWithContentDescription("금액 한도 사용").performScrollTo().performClick()
        compose.onNodeWithContentDescription("도시락 1개 최대금액 (원)").assertDoesNotExist()
        compose.onNodeWithText("금액 입력 필요", substring = true).assertDoesNotExist()
        compose.onNodeWithText("저장").performScrollTo().performClick()
        compose.onNodeWithText("설정 저장됨").assertExists()
        compose.onNodeWithContentDescription("설정 목록으로").performScrollTo().performClick()
        openStartDialog()
        compose.onNode(hasText("금액 한도 사용 안 함 · 가격 상한 보호 없음") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("주간근무일 자동예약: 꺼짐 · 저장한 수동 예약만 실행") and hasAnyAncestor(isDialog())).assertExists()
        recurringConsent().performClick()
        compose.onNodeWithText("동의하고 켜기").assertIsEnabled().performClick()
        compose.runOnIdle {
            val start = action as UiAction.SaveAndArmRecurring
            assertFalse(start.snapshot.settings.limitEnabled); assertFalse(start.snapshot.settings.patternConfirmed)
            assertFalse(start.snapshot.settings.dayAutoEnabled); assertNull(start.snapshot.settings.unitLimit)
            assertNull(start.snapshot.settings.orderLimit); assertTrue(start.acceptedPriceRisk)
        }
    }

    private fun saveDialogImage(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null) ?: context.cacheDir, "ui-evidence")
        directory.mkdirs()
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        FileOutputStream(File(directory, name)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }



    @Test fun editsMadeWhileSaveIsPendingAreNotOverwrittenBySaveAcknowledgement() {
        val current = mutableStateOf(readyState())
        var pending: UiAction.SaveSettings? = null
        compose.setContent { MaterialTheme { SettingsScreen(current.value, { if (it is UiAction.SaveSettings) pending = it }, onDate = {}, onSite = {}) } }
        openCategory(SettingsSection.ORDER)
        compose.onNodeWithContentDescription("도시락 1개 최대금액 (원)").performTextReplacement("6000")
        compose.onNodeWithText("저장").performScrollTo().performClick()
        compose.onNodeWithContentDescription("도시락 1개 최대금액 (원)").performScrollTo().performTextReplacement("7000")
        compose.runOnIdle { current.value = current.value.copy(settings = pending!!.settings.copy(generation = 1),
            settingsSaveResult = SettingsSaveResult(pending!!.requestId, true)) }
        compose.onNodeWithContentDescription("도시락 1개 최대금액 (원)").assertTextEquals("7000")
        compose.onNodeWithText("설정 저장됨 · 추가 편집 내용 유지").assertExists()
    }

    @Test fun existingUsersAlwaysSeeCategoryHomeWhetherAutomaticIsOffOrOn() {
        val current = mutableStateOf(readyState())
        compose.setContent { MaterialTheme { SettingsScreen(current.value, {}, onDate = {}, onSite = {}) } }
        SettingsSection.entries.forEach { compose.onNodeWithTag("settings_category_${it.name}").performScrollTo().assertExists() }
        compose.onNodeWithText("처음 설정").assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(settings = current.value.settings.copy(masterEnabled = true, liveScope = LiveScope.RECURRING, displayPriceRiskAccepted = true)) }
        compose.onNodeWithContentDescription("자동주문").performScrollTo().assertIsOn()
        compose.onNodeWithText("처음 설정").assertDoesNotExist()
    }

    @Test fun workSectionSavesWithoutMissingAmountsFromOtherSections() {
        var action: UiAction? = null
        compose.setContent { MaterialTheme { SettingsScreen(UiState(), { action = it }, onDate = {}, onSite = {}) } }
        openCategory(SettingsSection.WORK)
        compose.onNodeWithText("근무조: A조").performClick()
        compose.onNodeWithText("B조").performClick()
        compose.onNodeWithText("저장").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            val save = action as UiAction.SaveSettings
            assertEquals(ShiftType.B, save.settings.shiftType)
            assertNull(save.settings.unitLimit); assertNull(save.settings.orderLimit)
            assertFalse(save.settings.masterEnabled)
        }
    }

    @Test fun previouslyApprovedSameAccountResumesWithoutAnotherCheckbox() {
        val initial = readyState()
        val state = initial.copy(settings = initial.settings.copy(liveScope = LiveScope.RECURRING, displayPriceRiskAccepted = true))
        var action: UiAction? = null
        compose.setContent { MaterialTheme { SettingsScreen(state, { action = it }, onDate = {}, onSite = {}) } }
        openStartDialog()
        compose.onNode(hasText("반복 실제구매와 가격 변동·한도 초과 가능성에 동의합니다.")).assertDoesNotExist()
        compose.onNode(hasText("켜기") and hasAnyAncestor(isDialog())).assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue((action as UiAction.SaveAndArmRecurring).acceptedPriceRisk) }
    }

    @Test fun backFromDirtySectionPreservesInputsUntilExplicitDiscard() {
        compose.setContent { MaterialTheme { SettingsScreen(readyState(), {}, onDate = {}, onSite = {}) } }
        openCategory(SettingsSection.ORDER)
        compose.onNodeWithContentDescription("수량 (1~5개)").performTextReplacement("3")
        compose.onNodeWithContentDescription("설정 목록으로").performScrollTo().performClick()
        compose.onNodeWithText("변경 내용을 버릴까요?").assertExists()
        compose.onNodeWithText("계속 편집").performClick()
        compose.onNodeWithContentDescription("수량 (1~5개)").assertTextEquals("3")
        compose.onNodeWithContentDescription("설정 목록으로").performScrollTo().performClick()
        compose.onNodeWithText("변경 버리기").performClick()
        compose.onNodeWithTag("settings_category_ORDER").assertExists()
        openCategory(SettingsSection.ORDER)
        compose.onNodeWithContentDescription("수량 (1~5개)").assertTextEquals("1")
    }

    @Test fun failedSaveKeepsInputAndCanBeRetriedWithSameFailureMessage() {
        val current = mutableStateOf(readyState().copy(message = "저장 실패"))
        var requested: UiAction.SaveSettings? = null
        compose.setContent { MaterialTheme { SettingsScreen(current.value, { if (it is UiAction.SaveSettings) { requested = it; current.value = current.value.copy(busy = true) } }, onDate = {}, onSite = {}) } }
        openCategory(SettingsSection.ORDER)
        compose.onNodeWithContentDescription("도시락 1개 최대금액 (원)").performTextReplacement("6000")
        compose.onNodeWithText("저장").performScrollTo().performClick()
        compose.runOnIdle { current.value = current.value.copy(busy = false, settingsSaveResult = SettingsSaveResult(requested!!.requestId, false)) }
        compose.onNodeWithText("저장하지 못했습니다. 입력값은 유지됩니다.").assertExists()
        compose.onNodeWithContentDescription("도시락 1개 최대금액 (원)").assertTextEquals("6000")
        compose.onNodeWithText("저장").performScrollTo().assertIsEnabled()
    }

    @Test fun limitOffSaveWaitsForIdleAndGenerationDespiteEarlySuccessMessage() {
        val original = settings.copy(masterEnabled = true, liveScope = LiveScope.RECURRING,
            displayPriceRiskAccepted = true, unitLimit = null, orderLimit = null)
        val current = mutableStateOf(readyState().copy(settings = original))
        var requested: UiAction.SaveSettings? = null
        compose.setContent { MaterialTheme { SettingsScreen(current.value, { action ->
            if (action is UiAction.SaveSettings) { requested = action; current.value = current.value.copy(busy = true) }
        }, onDate = {}, onSite = {}) } }
        openCategory(SettingsSection.ORDER)
        compose.onNodeWithContentDescription("금액 한도 사용").performScrollTo().performClick()
        compose.onNodeWithText("저장").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { current.value = current.value.copy(message = "설정을 저장했습니다.") }
        compose.onNodeWithText("저장하지 못했습니다. 입력값은 유지됩니다.").assertDoesNotExist()
        compose.onNodeWithText("저장 중").assertExists()
        compose.runOnIdle { current.value = current.value.copy(settings = requested!!.settings.copy(generation = 1),
            settingsSaveResult = SettingsSaveResult(requested!!.requestId, true)) }
        compose.onNodeWithText("설정 저장됨").assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(busy = false) }
        compose.onNodeWithText("설정 저장됨").assertExists()
        compose.onNodeWithText("저장하지 못했습니다. 입력값은 유지됩니다.").assertDoesNotExist()
        compose.runOnIdle { assertFalse(current.value.settings.limitEnabled); assertNull(current.value.settings.unitLimit); assertNull(current.value.settings.orderLimit) }
    }

    @Test fun fastLimitOffSaveAcknowledgesWithoutBusyObservationAndPreservesOtherCategories() {
        val original = settings.copy(shiftType = ShiftType.B, dayAutoEnabled = true, patternConfirmed = true,
            useStoredCredentials = false, unitLimit = null, orderLimit = null, generation = 4, accountGeneration = 3,
            masterEnabled = true, liveScope = LiveScope.RECURRING, displayPriceRiskAccepted = true)
        val current = mutableStateOf(readyState().copy(settings = original))
        compose.setContent { MaterialTheme { SettingsScreen(current.value, { action ->
            if (action is UiAction.SaveSettings) current.value = current.value.copy(
                settings = action.settings.copy(generation = 5), message = "설정을 저장했습니다.",
                settingsSaveResult = SettingsSaveResult(action.requestId, true))
        }, onDate = {}, onSite = {}) } }
        openCategory(SettingsSection.ORDER)
        compose.onNodeWithContentDescription("금액 한도 사용").performScrollTo().performClick()
        compose.onNodeWithText("저장").performScrollTo().performClick()
        compose.onNodeWithText("설정 저장됨").assertExists()
        compose.onNodeWithText("저장하지 못했습니다. 입력값은 유지됩니다.").assertDoesNotExist()
        compose.runOnIdle {
            val saved = current.value.settings
            assertFalse(saved.limitEnabled); assertNull(saved.unitLimit); assertNull(saved.orderLimit)
            assertEquals(original.copy(limitEnabled = false, generation = 5), saved)
        }
    }

    @Test fun failedLimitOffSavePreservesOffDraftQuantityAndStoredCategories() {
        val original = settings.copy(shiftType = ShiftType.C, useStoredCredentials = false)
        val current = mutableStateOf(readyState().copy(settings = original))
        var requested: UiAction.SaveSettings? = null
        compose.setContent { MaterialTheme { SettingsScreen(current.value, { action ->
            if (action is UiAction.SaveSettings) { requested = action; current.value = current.value.copy(busy = true) }
        }, onDate = {}, onSite = {}) } }
        openCategory(SettingsSection.ORDER)
        compose.onNodeWithContentDescription("금액 한도 사용").performScrollTo().performClick()
        compose.onNodeWithContentDescription("수량 (1~5개)").performTextReplacement("3")
        compose.onNodeWithText("저장").performScrollTo().performClick()
        compose.runOnIdle { current.value = current.value.copy(busy = false, message = "저장 실패",
            settingsSaveResult = SettingsSaveResult(requested!!.requestId, false)) }
        compose.onNodeWithText("저장하지 못했습니다. 입력값은 유지됩니다.").assertExists()
        compose.onNodeWithContentDescription("금액 한도 사용").assertIsOff()
        compose.onNodeWithContentDescription("수량 (1~5개)").assertTextEquals("3")
        compose.onNodeWithText("저장").assertIsEnabled()
        compose.runOnIdle { assertEquals(original, current.value.settings) }
    }

    @Test fun failedOnboardingSaveDoesNotMarkFirstSetupComplete() {
        val current = mutableStateOf(UiState(onboardingRequired = true))
        val actions = mutableListOf<UiAction>()
        compose.setContent { MaterialTheme { SettingsScreen(current.value, { action -> actions += action; if (action is UiAction.SaveSettings) current.value = current.value.copy(busy = true) }, onDate = {}, onSite = {}) } }
        repeat(4) { compose.onNodeWithText("다음").performScrollTo().performClick() }
        compose.onNodeWithText("설정 완료").performScrollTo().performClick()
        compose.runOnIdle { current.value = current.value.copy(busy = false, message = "저장 실패",
            settingsSaveResult = SettingsSaveResult((actions.last { it is UiAction.SaveSettings } as UiAction.SaveSettings).requestId, false)) }
        compose.onNodeWithText("처음 설정").assertExists()
        compose.onNodeWithText("저장하지 못했습니다. 입력값은 유지됩니다.").assertExists()
        compose.runOnIdle { assertFalse(actions.contains(UiAction.CompleteOnboarding)); assertFalse(actions.any { it is UiAction.SaveAndArmRecurring }) }
    }

    @Test fun onboardingRejectsInvalidTimeAtCompletionPreservesDraftAndCompletesAfterCorrection() {
        val current = mutableStateOf(UiState(onboardingRequired = true,
            settings = AppSettings(limitEnabled = false)))
        val actions = mutableListOf<UiAction>()
        compose.setContent { MaterialTheme { SettingsScreen(current.value, { action ->
            actions += action
            when (action) {
                is UiAction.SaveSettings -> current.value = current.value.copy(
                    settings = action.settings.copy(generation = current.value.settings.generation + 1),
                    settingsSaveResult = SettingsSaveResult(action.requestId, true))
                UiAction.CompleteOnboarding -> current.value = current.value.copy(onboardingRequired = false)
                else -> Unit
            }
        }, onDate = {}, onSite = {}) } }
        repeat(2) { compose.onNodeWithText("다음").performScrollTo().performClick() }
        listOf("05:59:59", "08:00:00", "12:00:00", "malformed").forEach { time ->
            compose.onNodeWithContentDescription("자동주문 시각 (HH:mm:ss)").performScrollTo().performTextReplacement(time)
            repeat(2) { compose.onNodeWithText("다음").performScrollTo().performClick() }
            compose.onNodeWithText("5 / 5 · 완료").assertExists()
            compose.onNodeWithText(if (time == "malformed") "신청 시각을 HH:mm:ss로 입력해 주세요." else ReservationLimits.ORDER_TIME_ERROR).assertExists()
            compose.onNodeWithText("설정 완료").performScrollTo().assertIsNotEnabled().performClick()
            compose.runOnIdle {
                assertTrue(actions.isEmpty()); assertTrue(current.value.onboardingRequired)
                current.value = current.value.copy(settings = current.value.settings.copy(generation = current.value.settings.generation + 1))
            }
            compose.onNodeWithText(if (time == "malformed") "신청 시각을 HH:mm:ss로 입력해 주세요." else ReservationLimits.ORDER_TIME_ERROR).assertExists()
            repeat(2) { compose.onNodeWithText("이전").performScrollTo().performClick() }
            compose.onNodeWithContentDescription("자동주문 시각 (HH:mm:ss)").assertTextEquals(time)
        }
        listOf("06:00:00", "07:59:59").forEach { time ->
            compose.onNodeWithContentDescription("자동주문 시각 (HH:mm:ss)").performScrollTo().performTextReplacement(time)
            repeat(2) { compose.onNodeWithText("다음").performScrollTo().performClick() }
            compose.onNodeWithText("설정 완료").performScrollTo().assertIsEnabled()
            if (time == "06:00:00") repeat(2) { compose.onNodeWithText("이전").performScrollTo().performClick() }
        }
        compose.onNodeWithText("설정 완료").performClick()
        compose.onNodeWithTag("settings_category_ORDER").assertExists()
        compose.runOnIdle {
            val saved = actions.filterIsInstance<UiAction.SaveSettings>().single().settings
            assertEquals(LocalTime.of(7, 59, 59), saved.orderTime)
            assertFalse(saved.limitEnabled)
            assertNull(saved.unitLimit)
            assertNull(saved.orderLimit)
            assertEquals(1, actions.count { it == UiAction.CompleteOnboarding })
            assertFalse(current.value.onboardingRequired)
            assertFalse(actions.any { it is UiAction.SaveAndArmRecurring })
        }
    }

    @Test fun staleSettingsSaveAcknowledgementDoesNotFailRetriedRequest() {
        val current = mutableStateOf(readyState())
        val requests = mutableListOf<UiAction.SaveSettings>()
        compose.setContent { MaterialTheme { SettingsScreen(current.value, { action ->
            if (action is UiAction.SaveSettings) requests += action
        }, onDate = {}, onSite = {}) } }
        openCategory(SettingsSection.ORDER)
        compose.onNodeWithContentDescription("도시락 1개 최대금액 (원)").performTextReplacement("6000")
        compose.onNodeWithText("저장").performScrollTo().performClick()
        compose.runOnIdle { current.value = current.value.copy(settingsSaveResult = SettingsSaveResult(requests[0].requestId, false)) }
        compose.onNodeWithText("저장하지 못했습니다. 입력값은 유지됩니다.").assertExists()
        compose.onNodeWithText("저장").performScrollTo().performClick()
        compose.runOnIdle {
            assertNotEquals(requests[0].requestId, requests[1].requestId)
            current.value = current.value.copy(message = "이전 저장 실패", settingsSaveResult = SettingsSaveResult(requests[0].requestId, false))
        }
        compose.onNodeWithText("저장 중").assertExists()
        compose.onNodeWithText("저장하지 못했습니다. 입력값은 유지됩니다.").assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(settings = requests[1].settings.copy(generation = 1),
            settingsSaveResult = SettingsSaveResult(requests[1].requestId, true)) }
        compose.onNodeWithText("설정 저장됨").assertExists()
        compose.onNodeWithContentDescription("도시락 1개 최대금액 (원)").assertTextEquals("6000")
    }
}
