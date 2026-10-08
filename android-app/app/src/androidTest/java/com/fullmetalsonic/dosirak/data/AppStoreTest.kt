package com.fullmetalsonic.dosirak.data

import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
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
}
