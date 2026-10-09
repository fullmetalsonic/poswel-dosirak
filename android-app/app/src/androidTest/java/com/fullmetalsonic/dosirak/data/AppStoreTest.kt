package com.fullmetalsonic.dosirak.data

import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class AppStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val date = LocalDate.of(2026, 10, 12)

    @Test fun settingsExceptionsAndLedgerSurviveReopen() {
        val name = "test-${UUID.randomUUID()}.db"
        try {
            AppStore(context, name).use { store ->
                store.saveSettings(AppSettings(masterEnabled = true, liveScope = LiveScope.RECURRING))
                store.saveOverride(DateOverride(date, DatePolicy.EXCLUDE, reason = ReservationReason.VACATION))
                store.record(ExecutionRecord(date, 1, ExecutionStatus.NEEDS_CHECK, "SUBMIT_INTENT", "test", submissionPossible = true))
            }
            AppStore(context, name).use { store ->
                assertTrue(store.loadSettings().masterEnabled)
                assertEquals(ReservationReason.VACATION, store.loadOverrides()[date]?.reason)
                assertTrue(store.loadRecords().single().submissionPossible)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun backupNeverRestoresLiveAuthorizationOrOverwritesLedger() {
        val name = "test-${UUID.randomUUID()}.db"
        try {
            AppStore(context, name).use { store ->
                store.saveSettings(AppSettings(masterEnabled = true, liveScope = LiveScope.RECURRING,
                    displayPriceRiskAccepted = true, patternConfirmed = true, accountGeneration = 4,
                    verifiedLiveAccountGeneration = 4, liveBlockedReason = "BLOCK", generation = 10))
                store.record(ExecutionRecord(date, 1, ExecutionStatus.NEEDS_CHECK, "SUBMIT_INTENT", "test", submissionPossible = true))
                val text = store.exportPlanBackup()
                val exported = com.google.gson.JsonParser.parseString(text).asJsonObject
                assertFalse(exported.has("credentials"))
                assertFalse(exported.has("executions"))
                store.importPlanBackup(text)
                val restored = store.loadSettings()
                assertFalse(restored.masterEnabled)
                assertFalse(restored.displayPriceRiskAccepted)
                assertFalse(restored.patternConfirmed)
                assertEquals(LiveScope.NONE, restored.liveScope)
                assertNull(restored.verifiedLiveAccountGeneration)
                assertEquals(4L, restored.accountGeneration)
                assertEquals("BLOCK", restored.liveBlockedReason)
                assertTrue(store.loadRecords().single().submissionPossible)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun atomicMetadataUpdatePreservesUserOff() {
        val name = "test-${UUID.randomUUID()}.db"
        try {
            AppStore(context, name).use { store ->
                store.saveSettings(AppSettings(masterEnabled = false, generation = 9))
                store.updateSettings { it.copy(liveBlockedReason = "BLOCK") }
                assertFalse(store.loadSettings().masterEnabled)
                assertEquals(9L, store.loadSettings().generation)
                assertEquals("BLOCK", store.loadSettings().liveBlockedReason)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun overrideTransactionsAdvanceOnceAndPreserveLatestMetadataAndLedger() {
        val name = "test-${UUID.randomUUID()}.db"
        val settings = AppSettings(masterEnabled = false, dayAutoEnabled = true,
            liveScope = LiveScope.RECURRING, displayPriceRiskAccepted = true,
            accountGeneration = 4, liveBlockedReason = "BLOCK", generation = 23)
        val value = DateOverride(date, DatePolicy.MANUAL, 3, LocalTime.of(6, 17), ReservationReason.SUPPORT)
        val record = ExecutionRecord(date, 1, ExecutionStatus.NEEDS_CHECK, "SUBMIT_INTENT", "test", submissionPossible = true)
        try {
            AppStore(context, name).use { store ->
                store.saveSettings(settings)
                store.record(record)
                store.saveOverrideAndAdvanceGeneration(value)
                assertEquals(value, store.loadOverrides()[date])
                assertEquals(settings.copy(generation = 24), store.loadSettings())
                store.excludeOverrideAndAdvanceGeneration(DateOverride(date, DatePolicy.EXCLUDE,
                    reason = ReservationReason.VACATION))
                assertEquals(value.copy(policy = DatePolicy.EXCLUDE), store.loadOverrides()[date])
                assertEquals(settings.copy(generation = 25), store.loadSettings())
                store.deleteOverrideAndAdvanceGeneration(date)
                assertNull(store.loadOverrides()[date])
                assertEquals(settings.copy(generation = 26), store.loadSettings())
                assertEquals(listOf(record), store.loadRecords())
            }
            AppStore(context, name).use { store ->
                assertEquals(settings.copy(generation = 26), store.loadSettings())
                assertTrue(store.loadOverrides().isEmpty())
                assertEquals(listOf(record), store.loadRecords())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun failedConfigWriteRollsBackSavedOverrideAfterReopen() {
        assertOverrideRollback { store ->
            store.saveOverrideAndAdvanceGeneration(DateOverride(date, DatePolicy.MANUAL,
                5, LocalTime.of(6, 45), ReservationReason.OTHER))
        }
    }

    @Test fun failedConfigWriteRollsBackDeletedOverrideAfterReopen() {
        assertOverrideRollback { store -> store.deleteOverrideAndAdvanceGeneration(date) }
    }

    @Test fun failedConfigWriteRollsBackPriorityExclusionAfterReopen() {
        assertOverrideRollback { store ->
            store.excludeOverrideAndAdvanceGeneration(DateOverride(date, DatePolicy.EXCLUDE,
                reason = ReservationReason.VACATION))
        }
    }

    private fun assertOverrideRollback(change: (AppStore) -> Unit) {
        val name = "test-${UUID.randomUUID()}.db"
        val settings = AppSettings(masterEnabled = false, dayAutoEnabled = true, accountGeneration = 4,
            liveScope = LiveScope.RECURRING, displayPriceRiskAccepted = true,
            liveBlockedReason = "BLOCK", generation = 7)
        val original = DateOverride(date, DatePolicy.MANUAL, 2, LocalTime.of(6, 20), ReservationReason.SUBSTITUTE)
        val record = ExecutionRecord(date, 1, ExecutionStatus.NEEDS_CHECK, "SUBMIT_INTENT", "test", submissionPossible = true)
        try {
            AppStore(context, name).use { store ->
                store.saveSettings(settings)
                store.saveOverride(original)
                store.record(record)
                store.writableDatabase.execSQL("CREATE TRIGGER block_config BEFORE INSERT ON config BEGIN SELECT RAISE(ABORT, 'test'); END")
                assertThrows(RuntimeException::class.java) { change(store) }
                assertEquals(settings, store.loadSettings())
                assertEquals(mapOf(date to original), store.loadOverrides())
                assertEquals(listOf(record), store.loadRecords())
            }
            AppStore(context, name).use { store ->
                assertEquals(settings, store.loadSettings())
                assertEquals(mapOf(date to original), store.loadOverrides())
                assertEquals(listOf(record), store.loadRecords())
            }
        } finally { context.deleteDatabase(name) }
    }
}
