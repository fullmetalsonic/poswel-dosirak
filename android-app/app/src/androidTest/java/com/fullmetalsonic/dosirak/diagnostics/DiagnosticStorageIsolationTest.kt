package com.fullmetalsonic.dosirak.diagnostics

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.data.AppStore
import com.fullmetalsonic.dosirak.domain.AppSettings
import com.fullmetalsonic.dosirak.domain.DateOverride
import com.fullmetalsonic.dosirak.domain.DatePolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

class DiagnosticStorageIsolationTest {
    @Test fun lockedOrUnavailableUserReturnsNoOpBeforeCredentialEncryptedAccess() = runBlocking {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
            override fun getSystemService(name: String): Any? = if (name == Context.USER_SERVICE) null else super.getSystemService(name)
            override fun getApplicationContext(): Context = throw AssertionError("Application accessed before unlock")
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = throw AssertionError("CE preferences accessed before unlock")
            override fun getNoBackupFilesDir(): File = throw AssertionError("CE files accessed before unlock")
            override fun getCacheDir(): File = throw AssertionError("CE cache accessed before unlock")
        }
        val controller = AndroidDiagnosticProvider.get(context)
        controller.setEnabled(true)
        controller.safeRecord(DiagnosticEvent(DiagnosticEventType.RUN_STARTED))
        controller.clear()
        assertFalse(controller.status.value.enabled)
        assertEquals(DiagnosticStorageError.UNAVAILABLE, controller.status.value.error)
        assertNull(controller.snapshot())
    }

    @Test fun collectionToggleAndClearPreserveDatabaseSettingsGenerationAndLedger() = runBlocking {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getApplicationContext(): Context = this
        }
        val suffix = UUID.randomUUID().toString()
        val database = "diagnostic-isolation-$suffix.db"
        val root = File(context.cacheDir, "diagnostic-isolation-$suffix")
        val preferencesName = "diagnostic-isolation-$suffix"
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val store = DiagnosticStore(DiagnosticDisk(File(root, "analysis"), File(root, "analysis-share")),
            object : DiagnosticPreference {
                override fun readEnabled() = preferences.getBoolean("enabled", false)
                override fun writeEnabled(enabled: Boolean) = preferences.edit().putBoolean("enabled", enabled).commit()
            }, "1.0.2")
        try {
            AppStore(context, database).use { reservations ->
                reservations.saveSettings(AppSettings(generation = 123, accountGeneration = 2))
                reservations.saveOverride(DateOverride(LocalDate.of(2026, 10, 14), DatePolicy.MANUAL, 1))
                val before = Triple(reservations.loadSettings(), reservations.loadOverrides(), reservations.loadRecords())
                assertFalse(store.status.value.enabled)
                store.record(DiagnosticEvent(DiagnosticEventType.RUN_STARTED))
                store.awaitIdle()
                assertEquals(0L, store.status.value.bytes)
                store.setEnabled(true)
                store.record(DiagnosticEvent(DiagnosticEventType.RUN_FINISHED, result = DiagnosticResult.SUCCEEDED))
                val snapshot = requireNotNull(store.snapshot())
                assertTrue(snapshot.text.contains("RUN_FINISHED"))
                assertNotNull(store.cacheSnapshot(snapshot))
                store.clear()
                assertNull(store.cacheSnapshot(snapshot))
                assertEquals(before, Triple(reservations.loadSettings(), reservations.loadOverrides(), reservations.loadRecords()))
                assertTrue(preferences.getBoolean("enabled", false))
            }
        } finally {
            store.close()
            context.deleteDatabase(database)
            context.deleteSharedPreferences(preferencesName)
            root.deleteRecursively()
        }
    }
}
