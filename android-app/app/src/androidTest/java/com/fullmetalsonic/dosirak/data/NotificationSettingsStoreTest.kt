package com.fullmetalsonic.dosirak.data

import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NotificationSettingsStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun freshDefaultsDoNotWriteConfigAndLegacyReadDoesNotMigrateInDatabase() {
        val name = "notification-test-${UUID.randomUUID()}.db"
        try {
            AppStore(context, name).use { store ->
                assertTrue(store.loadSettings().effectiveNotifications().preparation.enabled)
                store.readableDatabase.rawQuery("SELECT COUNT(*) FROM config", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
                store.saveSettings(AppSettings(preparationAlert = false))
                fun storedJson(): String = store.readableDatabase.rawQuery("SELECT json FROM config WHERE id=1", null).use {
                    assertTrue(it.moveToFirst()); it.getString(0)
                }
                val before = storedJson()
                val legacy = store.loadSettings()
                assertNull(legacy.notifications); assertFalse(legacy.effectiveNotifications().preparation.enabled)
                assertEquals(before, storedJson())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun normalSavePersistsNormalizedDisabledChoicesAcrossReopen() {
        val name = "notification-test-${UUID.randomUUID()}.db"
        val options = AlertOptions(enabled = false, statusBar = false, popup = true, sound = true, vibration = true)
        val draft = AppSettings(notifications = NotificationPreferences(failure = options))
        try {
            AppStore(context, name).use { store ->
                val saved = ActivationRules.settingsForSave(store.loadSettings(), draft, null)
                store.saveSettings(saved)
            }
            AppStore(context, name).use { store ->
                val preferences = store.loadSettings().notifications!!
                assertFalse(preferences.failure.enabled); assertTrue(preferences.failure.statusBar)
                assertTrue(preferences.failure.popup); assertTrue(preferences.failure.sound); assertTrue(preferences.failure.vibration)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun legacyMediaNormalizesForReadSaveAndBackupWithoutOverwritingExplicitNotification() {
        val name = "notification-test-${UUID.randomUUID()}.db"
        try {
            AppStore(context, name).use { store ->
                val legacy = AppSettings(failureSoundMode = FailureSoundMode.MEDIA, mediaAlarmEnabled = true)
                val oldJson = AppJson.gson.toJson(legacy)
                store.writableDatabase.execSQL("INSERT INTO config(id,json) VALUES(1,?)", arrayOf(oldJson))
                val loaded = store.loadSettings()
                assertEquals(FailureSoundMode.ALARM, loaded.failureSoundMode)
                assertFalse(loaded.mediaAlarmEnabled)
                store.readableDatabase.rawQuery("SELECT json FROM config WHERE id=1", null).use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals(oldJson, cursor.getString(0))
                }
                store.saveSettings(loaded)
                val backup = com.google.gson.JsonParser.parseString(store.exportPlanBackup()).asJsonObject.getAsJsonObject("settings")
                assertEquals("ALARM", backup.get("failureSoundMode").asString)
                assertFalse(backup.get("mediaAlarmEnabled").asBoolean)
                store.saveSettings(loaded.copy(failureSoundMode = FailureSoundMode.NOTIFICATION))
            }
            AppStore(context, name).use { store ->
                assertEquals(FailureSoundMode.NOTIFICATION, store.loadSettings().failureSoundMode)
                val backup = com.google.gson.JsonParser.parseString(store.exportPlanBackup()).asJsonObject.getAsJsonObject("settings")
                assertEquals("NOTIFICATION", backup.get("failureSoundMode").asString)
            }
        } finally { context.deleteDatabase(name) }
    }
}
