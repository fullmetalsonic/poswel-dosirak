package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fullmetalsonic.dosirak.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class NotificationSettingsTest {
    @get:Rule val compose = createComposeRule()
    private fun tag(event: NotificationEvent, control: String) = "alert_${event.name}_$control"
    private fun expand(event: NotificationEvent) { compose.onNodeWithTag(tag(event, "details")).performScrollTo().performClick() }
    private fun permissionState(allowed: Boolean) = UiState(environment = listOf(EnvironmentStatus("notifications", "알림 권한",
        if (allowed) "확인됨" else "차단", "test")))

    @Test fun defaultsSeparatePreparationSuccessAndFailureMethods() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AlertSettings(UiState(), AppSettings(notifications = NotificationPreferences()), {}, {}) } } }
        NotificationEvent.entries.forEach { event ->
            expand(event)
            compose.onNodeWithTag(tag(event, "enabled")).assertIsOn()
            compose.onNodeWithTag(tag(event, "statusBar")).assertIsOn()
            listOf("popup", "sound", "vibration").forEach { method ->
                val node = compose.onNodeWithTag(tag(event, method))
                if (event == NotificationEvent.FAILURE) node.assertIsOn() else node.assertIsOff()
            }
        }
    }

    @Test fun switchingEventOffPreservesItsMethodsForReenable() {
        val current = mutableStateOf(AppSettings(notifications = NotificationPreferences()))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AlertSettings(UiState(), current.value, { current.value = it }, {}) } } }
        expand(NotificationEvent.FAILURE)
        compose.onNodeWithTag(tag(NotificationEvent.FAILURE, "enabled")).performScrollTo().performClick()
        compose.onNodeWithTag(tag(NotificationEvent.FAILURE, "test")).assertIsNotEnabled()
        compose.onNodeWithTag(tag(NotificationEvent.FAILURE, "sound")).assertIsOn().assertIsNotEnabled()
        compose.runOnIdle { assertFalse(current.value.effectiveNotifications().failure.enabled); assertTrue(current.value.effectiveNotifications().failure.popup) }
        compose.onNodeWithTag(tag(NotificationEvent.FAILURE, "enabled")).performScrollTo().performClick()
        compose.onNodeWithTag(tag(NotificationEvent.FAILURE, "test")).assertIsEnabled()
        compose.onNodeWithTag(tag(NotificationEvent.FAILURE, "vibration")).assertIsOn().assertIsEnabled()
    }

    @Test fun turningStatusBarOffClearsMethodsAndReenableStartsStatusOnly() {
        val current = mutableStateOf(AppSettings(notifications = NotificationPreferences()))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AlertSettings(UiState(), current.value, { current.value = it }, {}) } } }
        expand(NotificationEvent.FAILURE)
        compose.onNodeWithTag(tag(NotificationEvent.FAILURE, "statusBar")).performScrollTo().performClick()
        compose.onNodeWithTag(tag(NotificationEvent.FAILURE, "enabled")).assertIsOff()
        compose.runOnIdle { assertEquals(AlertOptions(false, false, false, false, false), current.value.effectiveNotifications().failure) }
        compose.onNodeWithTag(tag(NotificationEvent.FAILURE, "enabled")).performScrollTo().performClick()
        compose.onNodeWithTag(tag(NotificationEvent.FAILURE, "statusBar")).assertIsOn()
        listOf("popup", "sound", "vibration").forEach { compose.onNodeWithTag(tag(NotificationEvent.FAILURE, it)).assertIsOff() }
        compose.onNodeWithTag(tag(NotificationEvent.FAILURE, "sound")).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(current.value.effectiveNotifications().failure.statusBar); assertTrue(current.value.effectiveNotifications().failure.sound) }
    }

    @Test fun eachTestButtonUsesItsEventAndCurrentDraftWithoutOrdering() {
        val current = mutableStateOf(AppSettings(notifications = NotificationPreferences()))
        val actions = mutableListOf<UiAction>()
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AlertSettings(permissionState(true), current.value, { current.value = it }, { actions += it }) } } }
        expand(NotificationEvent.FAILURE)
        compose.onNodeWithTag(tag(NotificationEvent.FAILURE, "popup")).performScrollTo().performClick()
        compose.onNodeWithTag(tag(NotificationEvent.FAILURE, "vibration")).performScrollTo().performClick()
        NotificationEvent.entries.forEach { event ->
            compose.onNodeWithTag(tag(event, "test")).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(UiAction.TestNotification(event, current.value.effectiveNotifications()), actions.last()) }
        }
        compose.runOnIdle { assertTrue(actions.all { it is UiAction.TestNotification }); assertFalse(current.value.effectiveNotifications().failure.popup) }
    }

    @Test fun systemSettingsLinkAndUnsupportedMediaAreUnambiguous() {
        var action: UiAction? = null
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AlertSettings(UiState(mediaSupported = false), AppSettings(mediaAlarmEnabled = true), {}, { action = it }) } } }
        compose.onNodeWithText("실패 미디어 경보").assertDoesNotExist()
        compose.onNodeWithText("팝업·소리·진동은 휴대폰 알림 설정의 영향을 받습니다.").assertExists()
        compose.onNodeWithText("휴대폰 알림 설정").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(UiAction.OpenEnvironment("notifications"), action) }
    }

    @Test fun draftRestorationPreservesEventMethodsAndExecutionMetadata() {
        val restoration = StateRestorationTester(compose)
        val original = AppSettings(masterEnabled = true, liveScope = LiveScope.RECURRING, displayPriceRiskAccepted = true,
            generation = 41, accountGeneration = 7, liveBlockedReason = "ACCOUNT_TEST", mediaVolumePercent = 43,
            backgroundCheckEnabled = true, backgroundCheckMode = BackgroundCheckMode.HOURLY, backgroundCheckTime = LocalTime.of(7, 13),
            notifications = NotificationPreferences(failure = AlertOptions(enabled = false, popup = true, sound = true, vibration = true)))
        var restored = original
        restoration.setContent {
            var settings by rememberSaveable(stateSaver = SettingsDraftSaver) { mutableStateOf(original) }
            SideEffect { restored = settings }
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AlertSettings(UiState(), settings, { settings = it }, {}) } }
        }
        expand(NotificationEvent.SUCCESS)
        compose.onNodeWithTag(tag(NotificationEvent.SUCCESS, "vibration")).performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(tag(NotificationEvent.SUCCESS, "vibration")).assertIsOn()
        compose.runOnIdle {
            assertTrue(restored.effectiveNotifications().success.vibration)
            assertFalse(restored.effectiveNotifications().failure.enabled); assertTrue(restored.effectiveNotifications().failure.popup)
            assertTrue(restored.masterEnabled); assertEquals(LiveScope.RECURRING, restored.liveScope)
            assertEquals(41L, restored.generation); assertEquals(7L, restored.accountGeneration)
            assertEquals("ACCOUNT_TEST", restored.liveBlockedReason); assertEquals(43, restored.mediaVolumePercent)
            assertTrue(restored.backgroundCheckEnabled); assertEquals(LocalTime.of(7, 13), restored.backgroundCheckTime)
        }
    }

    @Test fun legacyNullPreferencesRemainNullAfterDraftRestoration() {
        val restoration = StateRestorationTester(compose)
        var restored = AppSettings()
        restoration.setContent {
            val settings by rememberSaveable(stateSaver = SettingsDraftSaver) { mutableStateOf(AppSettings(preparationAlert = false)) }
            SideEffect { restored = settings }
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AlertSettings(UiState(), settings, {}, {}) } }
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(tag(NotificationEvent.PREPARATION, "enabled")).assertIsOff()
        compose.runOnIdle { assertNull(restored.notifications); assertFalse(restored.preparationAlert); assertTrue(restored.effectiveNotifications().failure.sound) }
    }

    @Test fun alertAndAutomationSectionMergesPreserveOtherSavedValues() {
        val original = AppSettings(masterEnabled = true, liveScope = LiveScope.RECURRING, displayPriceRiskAccepted = true,
            generation = 8, accountGeneration = 2, defaultQuantity = 2, notifications = NotificationPreferences())
        val edited = original.copy(defaultQuantity = 5, backgroundCheckEnabled = true,
            notifications = NotificationPreferences(success = AlertOptions(sound = true)))
        val alert = sectionSettings(original, edited, SettingsSection.ALERTS)
        assertEquals(edited.notifications, alert.notifications); assertEquals(2, alert.defaultQuantity)
        assertFalse(alert.backgroundCheckEnabled); assertTrue(alert.masterEnabled); assertEquals(8L, alert.generation)
        val automation = sectionSettings(original, edited, SettingsSection.AUTOMATION)
        assertTrue(automation.backgroundCheckEnabled); assertEquals(original.notifications, automation.notifications)
        assertEquals(2, automation.defaultQuantity); assertEquals(original, sectionSettings(original, edited, SettingsSection.APP))
    }

    @Test fun largeFont360LayoutKeepsEveryMethodAndTestReachable() {
        val current = mutableStateOf(AppSettings(notifications = NotificationPreferences()))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { Column(Modifier.width(360.dp).verticalScroll(rememberScrollState()).padding(16.dp)) { AlertSettings(UiState(), current.value, { current.value = it }, {}) } }
            }
        }
        NotificationEvent.entries.forEach { event ->
            expand(event)
            listOf("enabled", "statusBar", "popup", "sound", "vibration", "test").forEach { control ->
                val node = compose.onNodeWithTag(tag(event, control)).performScrollTo().assertIsDisplayed()
                val bounds = node.fetchSemanticsNode().boundsInRoot
                compose.runOnIdle { assertTrue(bounds.width <= with(compose.density) { 360.dp.toPx() } + 1f); assertTrue(bounds.height > 0) }
            }
        }
        compose.onNodeWithText("휴대폰 알림 설정").performScrollTo().assertIsDisplayed()
    }

    @Test fun permissionOffNoticeIsFirstAndEnableAndTestOnlyRequestAccess() {
        val actions = mutableListOf<UiAction>()
        val original = AppSettings(masterEnabled = true, liveScope = LiveScope.RECURRING, displayPriceRiskAccepted = true,
            generation = 39, accountGeneration = 7, notifications = NotificationPreferences())
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            AlertSettings(permissionState(false), original, { fail("Permission access must not edit settings") }, { actions += it })
        } } }
        compose.onNodeWithText("앱 알림이 꺼져 있습니다").assertIsDisplayed()
        compose.onNodeWithText("알림 켜기").assertIsDisplayed().performClick()
        compose.onNodeWithTag(tag(NotificationEvent.FAILURE, "test")).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(UiAction.OpenEnvironment("notifications"), UiAction.OpenEnvironment("notifications")), actions) }
    }

    @Test fun unknownPermissionChecksInsteadOfPostingOrClaimingOff() {
        val actions = mutableListOf<UiAction>()
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            AlertSettings(UiState(), AppSettings(notifications = NotificationPreferences()), {}, { actions += it })
        } } }
        compose.onNodeWithText("알림 권한 확인 중").assertIsDisplayed()
        compose.onNodeWithText("앱 알림이 꺼져 있습니다").assertDoesNotExist()
        compose.onNodeWithText("알림 권한 확인").performClick()
        compose.onNodeWithTag(tag(NotificationEvent.SUCCESS, "test")).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(UiAction.RefreshEnvironment, UiAction.RefreshEnvironment), actions) }
    }

    @Test fun permissionAllowedRemovesNoticeButRequiresAnotherExplicitTestClick() {
        val current = mutableStateOf(permissionState(false))
        val actions = mutableListOf<UiAction>()
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            AlertSettings(current.value, AppSettings(notifications = NotificationPreferences()), {}, { actions += it })
        } } }
        compose.onNodeWithTag(tag(NotificationEvent.SUCCESS, "test")).performScrollTo().performClick()
        compose.runOnIdle { current.value = permissionState(true) }
        compose.onNodeWithTag("notification_permission_notice").assertDoesNotExist()
        compose.runOnIdle { assertTrue(actions.none { it is UiAction.TestNotification }) }
        compose.onNodeWithTag(tag(NotificationEvent.SUCCESS, "test")).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(actions.last() is UiAction.TestNotification) }
    }

    @Test fun everyEventHasItsOwnSystemChannelSettingsLink() {
        var action: UiAction? = null
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            AlertSettings(permissionState(true), AppSettings(notifications = NotificationPreferences()), {}, { action = it })
        } } }
        NotificationEvent.entries.forEach { event ->
            expand(event)
            compose.onNodeWithTag(tag(event, "system_settings")).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(UiAction.OpenNotificationSettings(event), action) }
        }
    }

    @Test fun permissionOffEnableButtonFits360AtLargeFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { Column(Modifier.width(360.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    AlertSettings(permissionState(false), AppSettings(), {}, {})
                } }
            }
        }
        val button = compose.onNodeWithTag("notification_permission_action").performScrollTo().assertIsDisplayed()
        val bounds = button.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { assertTrue(bounds.width <= with(compose.density) { 360.dp.toPx() }); assertTrue(bounds.height > 0) }
        compose.onNodeWithTag(tag(NotificationEvent.FAILURE, "test")).performScrollTo().assertIsDisplayed()
    }
}
