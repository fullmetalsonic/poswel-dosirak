package com.fullmetalsonic.dosirak.data

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.domain.*
import com.fullmetalsonic.dosirak.platform.IsolatedWarmupProbeRunner
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class ReleaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val databaseName = "release-migration-fixture.db"
    private val restoredDatabaseName = "release-migration-restored.db"
    private val backupFileName = "release-migration-plan.json"
    private val seedSettings = AppSettings(
        masterEnabled = false,
        dayAutoEnabled = false,
        shiftType = ShiftType.C,
        patternAnchor = LocalDate.of(2026, 10, 12),
        weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
        defaultQuantity = 2,
        orderTime = LocalTime.of(6, 25),
        limitEnabled = false,
        accountGeneration = 0,
        liveScope = LiveScope.NONE,
        displayPriceRiskAccepted = false,
        generation = 8
    )
    private val seedOverrides = listOf(
        DateOverride(LocalDate.of(2026, 10, 13), DatePolicy.MANUAL, 2, LocalTime.of(6, 30), ReservationReason.SUBSTITUTE),
        DateOverride(LocalDate.of(2026, 10, 14), DatePolicy.EXCLUDE, reason = ReservationReason.VACATION)
    )
    private val seedLedger = ExecutionRecord(
        LocalDate.of(2026, 10, 12), 1, ExecutionStatus.NEEDS_CHECK,
        "RELEASE_MIGRATION_FIXTURE", "Synthetic test record", updatedAt = Instant.parse("2026-10-12T00:00:00Z"),
        submissionPossible = true
    )

    @Test fun seedOrVerifyReleaseMigration() {
        assertEmulatorOptIn()
        when (InstrumentationRegistry.getArguments().getString("phase")) {
            "seed" -> seedBaselineState()
            "verify" -> verifyUpdatedStateAndBackupImport()
            else -> fail("Pass -e phase seed or verify explicitly.")
        }
    }

    private fun seedBaselineState() {
        context.deleteDatabase(databaseName)
        context.deleteDatabase(restoredDatabaseName)
        context.deleteFile(backupFileName)
        AppStore(context, databaseName).use { store ->
            store.saveSettings(seedSettings)
            seedOverrides.forEach(store::saveOverride)
            store.record(seedLedger)
            context.openFileOutput(backupFileName, 0).bufferedWriter(Charsets.UTF_8).use {
                it.write(store.exportPlanBackup())
            }
        }
    }

    private fun verifyUpdatedStateAndBackupImport() {
        AppStore(context, databaseName).use { store ->
            assertEquals(seedSettings, store.loadSettings())
            assertEquals(seedOverrides.associateBy { it.date }, store.loadOverrides())
            assertEquals(listOf(seedLedger), store.loadRecords())
        }

        val backup = context.openFileInput(backupFileName).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val exported = com.google.gson.JsonParser.parseString(backup).asJsonObject
        assertFalse(exported.has("credentials"))
        assertFalse(exported.has("executions"))

        context.deleteDatabase(restoredDatabaseName)
        AppStore(context, restoredDatabaseName).use { restored ->
            restored.importPlanBackup(backup)
            val settings = restored.loadSettings()
            assertEquals(seedSettings.shiftType, settings.shiftType)
            assertEquals(seedSettings.weekdays, settings.weekdays)
            assertEquals(seedSettings.defaultQuantity, settings.defaultQuantity)
            assertEquals(seedSettings.orderTime, settings.orderTime)
            assertEquals(seedOverrides.associateBy { it.date }, restored.loadOverrides())
            assertFalse(settings.masterEnabled)
            assertFalse(settings.dayAutoEnabled)
            assertFalse(settings.displayPriceRiskAccepted)
            assertFalse(settings.patternConfirmed)
            assertEquals(LiveScope.NONE, settings.liveScope)
            assertEquals(0L, settings.accountGeneration)
            assertNull(settings.verifiedLiveAccountGeneration)
            assertTrue(restored.loadRecords().isEmpty())
        }
    }

    private fun assertEmulatorOptIn() {
        check(instrumentation is IsolatedWarmupProbeRunner) {
            "Release migration probes require the isolated instrumentation runner."
        }
        check(context.applicationContext.javaClass == android.app.Application::class.java) {
            "Release migration probes require the plain Application fixture."
        }
        check(InstrumentationRegistry.getArguments().getString("poswelReleaseMigration") == "true") {
            "Release migration probes require explicit -e poswelReleaseMigration true."
        }
        val fingerprint = Build.FINGERPRINT.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val knownAvd = fingerprint.contains("sdk_phone") && hardware in setOf("ranchu", "goldfish")
        check(fingerprint.contains("generic") || fingerprint.contains("emulator") || knownAvd) {
            "Release migration probes are restricted to emulator builds."
        }
    }
}
