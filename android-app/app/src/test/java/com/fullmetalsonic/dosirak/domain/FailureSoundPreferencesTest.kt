package com.fullmetalsonic.dosirak.domain

import com.fullmetalsonic.dosirak.data.AppJson
import com.fullmetalsonic.dosirak.platform.FailureAlarmService
import org.junit.Assert.*
import org.junit.Test

class FailureSoundPreferencesTest {
    @Test fun implicitDefaultsAndLegacyMediaUseAlarmWhileExplicitNotificationIsPreserved() {
        val old = AppJson.gson.fromJson("{}", AppSettings::class.java)
        val oldMedia = AppJson.gson.fromJson("{\"mediaAlarmEnabled\":true}", AppSettings::class.java)
        assertEquals(FailureSoundMode.ALARM, AppSettings().effectiveFailureSoundMode())
        assertEquals(FailureSoundMode.ALARM, old.effectiveFailureSoundMode())
        assertEquals(FailureSoundMode.ALARM, oldMedia.effectiveFailureSoundMode())
        assertEquals(FailureSoundMode.ALARM, AppSettings(failureSoundMode = null).effectiveFailureSoundMode())
        val selected = AppJson.gson.fromJson("{\"failureSoundMode\":\"NOTIFICATION\",\"mediaAlarmEnabled\":true}", AppSettings::class.java)
        assertEquals(FailureSoundMode.NOTIFICATION, selected.effectiveFailureSoundMode())
    }

    @Test fun eachModeAndBoundedValuesRoundTripAndActivationPreservesSelection() {
        FailureSoundMode.entries.forEach { mode ->
            val original = AppSettings(failureSoundMode = mode, mediaVolumePercent = 43, mediaDurationSeconds = 7)
            val restored = AppJson.gson.fromJson(AppJson.gson.toJson(original), AppSettings::class.java)
            assertEquals(original, restored)
            val edited = ActivationRules.editedSettings(AppSettings(), restored)
            assertEquals(if (mode == FailureSoundMode.MEDIA) FailureSoundMode.ALARM else mode, edited.effectiveFailureSoundMode())
            assertFalse(edited.mediaAlarmEnabled)
        }
        assertEquals(FailureSoundRequest(FailureSoundMode.ALARM, 100, 60), FailureSoundRequest(FailureSoundMode.ALARM, 200, 100).normalized())
        assertEquals(FailureSoundRequest(FailureSoundMode.ALARM, 0, 1), FailureSoundRequest(FailureSoundMode.MEDIA, -1, -1).normalized())
    }

    @Test fun target37MediaNeverPretendsBackgroundSupport() {
        assertTrue(FailureAlarmService.mediaRestricted(37, false))
        assertFalse(FailureAlarmService.mediaRestricted(37, true))
        assertFalse(FailureAlarmService.mediaRestricted(36, false))
    }

    @Test fun storedNormalizationOnlyChangesLegacySoundFields() {
        val legacy = AppSettings(mediaAlarmEnabled = true, failureSoundMode = FailureSoundMode.MEDIA,
            generation = 42, accountGeneration = 7, defaultQuantity = 2, mediaDurationSeconds = 10)
        val normalized = legacy.normalizedFailureSound()
        assertEquals(legacy.copy(mediaAlarmEnabled = false, failureSoundMode = FailureSoundMode.ALARM), normalized)
        assertEquals(normalized, normalized.normalizedFailureSound())
    }

    @Test fun sameRealEventDoesNotRestartWithinPlaybackWindow() {
        assertFalse(FailureAlarmService.duplicate(9876, 100_000))
        assertTrue(FailureAlarmService.duplicate(9876, 100_001))
        assertFalse(FailureAlarmService.duplicate(9877, 100_002))
        assertFalse(FailureAlarmService.duplicate(9877, 160_003))
    }
}
