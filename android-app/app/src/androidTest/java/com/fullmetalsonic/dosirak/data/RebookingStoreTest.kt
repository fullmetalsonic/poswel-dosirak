package com.fullmetalsonic.dosirak.data

import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class RebookingStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val date = LocalDate.of(2026, 10, 17)
    private val now = Instant.parse("2026-10-16T21:14:00Z")
    private val original = ExecutionRecord(date, 1, ExecutionStatus.COMPLETED, "VERIFIED", "native", now,
        5000, "private-server-id", true, 4, 8)
    private val state = RebookingState(date, "private-account", 4, original, "menu", emptyList(), now,
        listOf("private-cancel-key"), now)
    private val planned = ExecutionRecord(date, 1, ExecutionStatus.PLANNED, "REBOOK_ARMED", "planned", now,
        accountGeneration = 4, generation = 9)
    private val override = DateOverride(date, DatePolicy.MANUAL, 1, LocalTime.of(6, 16))
    private val armed = state.copy(permit = RebookingPermit("private-permit", 1, LocalTime.of(6, 16), 9))

    @Test fun armArchivePermitAndPlanSurviveReopenAndConsumeOnce() {
        val name = "test-rebook-${UUID.randomUUID()}.db"
        try {
            AppStore(context, name).use { store ->
                store.saveSettings(AppSettings(accountGeneration = 4, generation = 8))
                store.record(original)
                store.saveRebookingState(state)
                store.armRebooking(state, armed, override, planned, 8, 4)
            }
            AppStore(context, name).use { store ->
                assertEquals(9L, store.loadSettings().generation)
                assertEquals(override, store.loadOverrides()[date])
                assertEquals(planned, store.loadRecords().single())
                assertTrue(store.loadArchivedRecords().contains(original))
                val intent = planned.copy(status = ExecutionStatus.RUNNING, stage = "TEMP_INTENT", submissionPossible = true)
                assertTrue(store.consumeRebookingAndRecord(date, intent))
                assertFalse(store.consumeRebookingAndRecord(date, intent))
                assertTrue(store.rebookingState(date)!!.permit!!.consumed)
                assertEquals(intent, store.loadRecords().single())
                val backup = store.exportPlanBackup()
                assertFalse(backup.contains("private-"))
                assertFalse(backup.contains("rebook"))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun staleArmRollsBackWithoutChangingOriginalOrPlan() {
        val name = "test-rebook-${UUID.randomUUID()}.db"
        try {
            AppStore(context, name).use { store ->
                store.saveSettings(AppSettings(accountGeneration = 5, generation = 8))
                store.record(original)
                store.saveRebookingState(state)
                try { store.armRebooking(state, armed, override, planned, 8, 4); fail("account mismatch") }
                catch (_: IllegalStateException) { }
                assertEquals(original, store.loadRecords().single())
                assertTrue(store.loadOverrides().isEmpty())
                assertNull(store.rebookingState(date)!!.permit)
                assertTrue(store.loadArchivedRecords().isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun consumedPermitCannotRearmAndUnknownLedgerIsPreserved() {
        val name = "test-rebook-${UUID.randomUUID()}.db"
        try {
            AppStore(context, name).use { store ->
                store.saveSettings(AppSettings(accountGeneration = 4, generation = 8))
                store.record(original)
                store.saveRebookingState(state)
                store.armRebooking(state, armed, override, planned, 8, 4)
                val intent = planned.copy(status = ExecutionStatus.RUNNING, stage = "TEMP_INTENT", submissionPossible = true)
                assertTrue(store.consumeRebookingAndRecord(date, intent))
                for (stage in listOf("TEMP_UNKNOWN", "SUBMIT_UNKNOWN")) {
                    val unknown = intent.copy(status = ExecutionStatus.NEEDS_CHECK, stage = stage)
                    store.record(unknown)
                    val consumed = requireNotNull(store.rebookingState(date))
                    val archived = store.loadArchivedRecords()
                    val replacement = consumed.copy(permit = RebookingPermit("replacement-permit", 1, LocalTime.of(6, 16), 10))
                    try {
                        store.armRebooking(consumed, replacement, override, planned.copy(generation = 10), 9, 4)
                        fail("consumed permit must never clear $stage")
                    } catch (_: IllegalStateException) { }
                    assertEquals(unknown, store.loadRecords().single())
                    assertEquals(9L, store.loadSettings().generation)
                    assertEquals(consumed, store.rebookingState(date))
                    assertEquals(archived, store.loadArchivedRecords())
                    assertEquals(override, store.loadOverrides()[date])
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun permitNullUnknownLedgerCannotBeReplacedByPlanned() {
        val name = "test-rebook-${UUID.randomUUID()}.db"
        try {
            AppStore(context, name).use { store ->
                store.saveSettings(AppSettings(accountGeneration = 4, generation = 8))
                store.saveRebookingState(state)
                for (stage in listOf("TEMP_UNKNOWN", "SUBMIT_UNKNOWN", "TEMP_INTENT", "SUBMIT_INTENT")) {
                    val unknown = original.copy(status = ExecutionStatus.NEEDS_CHECK, stage = stage)
                    store.record(unknown)
                    val archived = store.loadArchivedRecords()
                    try {
                        store.armRebooking(state, armed, override, planned, 8, 4)
                        fail("permit-null $stage must not clear unknown ledger")
                    } catch (_: IllegalStateException) { }
                    assertEquals(unknown, store.loadRecords().single())
                    assertEquals(8L, store.loadSettings().generation)
                    assertNull(store.rebookingState(date)!!.permit)
                    assertEquals(archived, store.loadArchivedRecords())
                    assertTrue(store.loadOverrides().isEmpty())
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun ignoredArchiveInsertStopsOverwriteAndRollsBackArming() {
        val name = "test-rebook-${UUID.randomUUID()}.db"
        try {
            AppStore(context, name).use { store ->
                store.saveSettings(AppSettings(accountGeneration = 4, generation = 8))
                store.record(original)
                store.saveRebookingState(state)
                store.writableDatabase.execSQL("CREATE TRIGGER reject_archive BEFORE INSERT ON execution_archive BEGIN SELECT RAISE(IGNORE); END")
                try { store.record(original.copy(stage = "SUBMIT_UNKNOWN", status = ExecutionStatus.NEEDS_CHECK)); fail("archive failure must stop overwrite") }
                catch (_: IllegalStateException) { }
                assertEquals(original, store.loadRecords().single())
                try { store.armRebooking(state, armed, override, planned, 8, 4); fail("archive failure must roll back arming") }
                catch (_: IllegalStateException) { }
                assertEquals(original, store.loadRecords().single())
                assertEquals(8L, store.loadSettings().generation)
                assertEquals(state, store.rebookingState(date))
                assertTrue(store.loadOverrides().isEmpty())
                assertTrue(store.loadArchivedRecords().isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun staleRebookingIsProjectedWithoutChangingStoredRecordOrOtherDates() {
        val name = "test-rebook-${UUID.randomUUID()}.db"
        val ready = planned.copy(generation = 8)
        val other = original.copy(date = date.minusDays(1))
        try {
            AppStore(context, name).use { store ->
                val settings = AppSettings(accountGeneration = 4, generation = 8)
                store.saveSettings(settings)
                store.record(ready)
                store.record(other)
                assertEquals(ready, store.loadRecords().first { it.date == date })
                for (changed in listOf(settings.copy(generation = 9), settings.copy(accountGeneration = 5))) {
                    store.saveSettings(changed)
                    val projected = store.loadRecords().first { it.date == date }
                    assertEquals(ExecutionStatus.NEEDS_CHECK, projected.status)
                    assertEquals("REBOOK_STALE", projected.stage)
                    assertEquals(other, store.loadRecords().first { it.date == other.date })
                    val stored = store.readableDatabase.rawQuery("SELECT json FROM executions WHERE date=?", arrayOf(date.toString())).use {
                        assertTrue(it.moveToFirst())
                        AppJson.gson.fromJson(it.getString(0), ExecutionRecord::class.java)
                    }
                    assertEquals(ready, stored)
                }
                store.saveSettings(settings)
                assertEquals(ready, store.loadRecords().first { it.date == date })
                assertTrue(store.loadArchivedRecords().isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun schemaOneUpgradeKeepsAllExistingExecutionAndSettings() {
        val name = "test-rebook-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("CREATE TABLE config (id INTEGER PRIMARY KEY CHECK(id=1), json TEXT NOT NULL)")
                db.execSQL("CREATE TABLE exceptions (date TEXT PRIMARY KEY, json TEXT NOT NULL)")
                db.execSQL("CREATE TABLE executions (date TEXT PRIMARY KEY, json TEXT NOT NULL)")
                db.execSQL("INSERT INTO executions(date,json) VALUES(?,?)", arrayOf(date.toString(), AppJson.gson.toJson(original)))
                db.version = 1
            }
            AppStore(context, name).use { store ->
                assertEquals(original, store.loadRecords().single())
                assertEquals(2, store.readableDatabase.version)
                assertNull(store.rebookingState(date))
                assertTrue(store.loadArchivedRecords().isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }
}
