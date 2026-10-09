package com.fullmetalsonic.dosirak.domain

import com.fullmetalsonic.dosirak.data.AppJson
import org.junit.Assert.*
import org.junit.Test

class NotificationPreferencesTest {
    @Test fun freshDefaultsAndLegacyPreparationRemainDifferent() {
        val fresh = AppSettings(notifications = NotificationPreferences()).effectiveNotifications()
        assertTrue(fresh.preparation.enabled); assertTrue(fresh.success.enabled); assertTrue(fresh.failure.enabled)
        assertEquals(0, fresh.preparation.profile()); assertEquals(0, fresh.success.profile()); assertEquals(7, fresh.failure.profile())
        assertFalse(AppSettings(preparationAlert = false).effectiveNotifications().preparation.enabled)
        assertTrue(AppSettings(preparationAlert = true).effectiveNotifications().preparation.enabled)
    }

    @Test fun everyOptionsCombinationNormalizesAndPreservesDisabledMethods() {
        for (bits in 0..31) {
            val raw = AlertOptions(bits and 16 != 0, bits and 8 != 0, bits and 4 != 0, bits and 2 != 0, bits and 1 != 0)
            val normalized = raw.normalized()
            val anyMethod = raw.statusBar || raw.popup || raw.sound || raw.vibration
            assertEquals(raw.enabled && anyMethod, normalized.enabled)
            assertEquals(anyMethod, normalized.statusBar)
            assertEquals(raw.popup, normalized.popup); assertEquals(raw.sound, normalized.sound)
            assertEquals(raw.vibration, normalized.vibration)
            assertEquals(normalized, normalized.normalized())
        }
    }

    @Test fun allOffDisablesAndOtherMethodsRequireStatusBar() {
        assertFalse(AlertOptions(statusBar = false).normalized().enabled)
        assertTrue(AlertOptions(statusBar = false, sound = true).normalized().statusBar)
        val disabled = AlertOptions(enabled = false, popup = true, sound = true, vibration = true).normalized()
        assertFalse(disabled.enabled); assertEquals(7, disabled.profile())
        assertEquals(disabled.copy(enabled = true), disabled.copy(enabled = true).normalized())
    }

    @Test fun explicitPreferencesOverrideLegacyAndNormalSaveMigratesWithoutChangingInternalFields() {
        val current = AppSettings(preparationAlert = true, generation = 8, accountGeneration = 4)
        val preferences = NotificationPreferences(preparation = AlertOptions(enabled = false), success = AlertOptions(sound = true))
        val saved = ActivationRules.editedSettings(current, current.copy(preparationAlert = false, notifications = preferences))
        assertEquals(preferences.normalized(), saved.notifications)
        assertTrue(saved.preparationAlert); assertFalse(saved.effectiveNotifications().preparation.enabled)
        assertEquals(current.accountGeneration, saved.accountGeneration); assertEquals(current.generation, saved.generation)
        val migrated = ActivationRules.editedSettings(current, current)
        assertTrue(migrated.notifications!!.preparation.enabled)
    }

    @Test fun legacyAndNewJsonRoundTripKeepsDisabledMethodChoices() {
        val legacyJson = AppJson.gson.toJsonTree(AppSettings(preparationAlert = true)).asJsonObject.apply { remove("notifications") }
        val legacy = AppJson.gson.fromJson(legacyJson, AppSettings::class.java)
        assertNull(legacy.notifications); assertTrue(legacy.effectiveNotifications().preparation.enabled)
        val preferences = NotificationPreferences(failure = AlertOptions(enabled = false, popup = true, sound = true, vibration = true))
        val settings = AppSettings(notifications = preferences)
        val restored = AppJson.gson.fromJson(AppJson.gson.toJson(settings), AppSettings::class.java)
        assertEquals(settings, restored); assertFalse(restored.effectiveNotifications().failure.enabled)
        assertEquals(7, restored.effectiveNotifications().failure.profile())
    }

    @Test fun profilesAreFiniteAndUniqueForEightEffectCombinations() {
        val profiles = (0..7).map { bits -> AlertOptions(popup = bits and 4 != 0, sound = bits and 2 != 0,
            vibration = bits and 1 != 0).profile() }
        assertEquals((0..7).toList(), profiles)
        NotificationEvent.values().forEach { event -> assertNotNull(NotificationPreferences().options(event)) }
    }
}
