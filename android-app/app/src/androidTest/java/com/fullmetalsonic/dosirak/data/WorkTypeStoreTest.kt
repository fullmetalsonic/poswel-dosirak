package com.fullmetalsonic.dosirak.data

import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.domain.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class WorkTypeStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val date = LocalDate.of(2026, 10, 15)

    private fun withStore(block: (AppStore, String) -> Unit) {
        val name = "worktypes-${UUID.randomUUID()}.db"
        try { AppStore(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }

    @Test fun legacyMissingCatalogDefaultsWithoutConvertingReasonsToWork() = withStore { store, _ ->
        val raw = JsonParser.parseString(AppJson.gson.toJson(AppSettings(limitEnabled = false))).asJsonObject
        raw.remove("workTypes")
        store.writableDatabase.execSQL("INSERT INTO config(id,json) VALUES(1,?)", arrayOf(raw.toString()))
        val original = DateOverride(date, DatePolicy.EXCLUDE, reason = ReservationReason.VACATION)
        store.saveOverride(original)
        assertEquals(WorkTypes.builtIns, store.loadSettings().workTypes)
        assertEquals(original, store.loadOverrides()[date])
        assertNull(store.loadOverrides()[date]?.workTypeId)
    }

    @Test fun atomicBatchAndRememberedDefaultAdvanceOncePreservingLedgerAndInheritance() = withStore { store, name ->
        val initial = AppSettings(limitEnabled = false, generation = 5)
        store.saveSettings(initial)
        val original = DateOverride(date, DatePolicy.AUTO)
        store.saveOverride(original)
        val record = ExecutionRecord(date, 1, ExecutionStatus.NEEDS_CHECK, "SUBMIT_INTENT", "test", submissionPossible = true)
        store.record(record)
        val second = date.plusDays(1)
        val replacement = original.copy(policy = DatePolicy.MANUAL, workTypeId = WorkTypes.EDUCATION)
        assertTrue(store.saveDateEdits(mapOf(date to replacement, second to DateOverride(second, DatePolicy.EXCLUDE, workTypeId = WorkTypes.VACATION)),
            5, mapOf(date to original, second to null), mapOf(WorkTypes.EDUCATION to true)))
        assertEquals(6L, store.loadSettings().generation)
        assertEquals(true, WorkTypes.definition(WorkTypes.EDUCATION, store.loadSettings())?.mealDefault)
        assertNull(store.loadOverrides()[date]?.quantity)
        assertNull(store.loadOverrides()[date]?.time)
        assertEquals(listOf(record), store.loadRecords())
        AppStore(context, name).use { reopened -> assertEquals(replacement, reopened.loadOverrides()[date]) }
    }

    @Test fun unchangedSaveDoesNotConvertAutoOrAdvanceAndRestoreOnlyRemovesException() = withStore { store, _ ->
        store.saveSettings(AppSettings(limitEnabled = false, generation = 9))
        val original = DateOverride(date, DatePolicy.AUTO, time = LocalTime.of(6, 17))
        store.saveOverride(original)
        assertFalse(store.saveDateEdits(mapOf(date to original), 9, mapOf(date to original)))
        assertEquals(9L, store.loadSettings().generation)
        assertTrue(store.saveDateEdits(mapOf(date to null), 9, mapOf(date to original)))
        assertNull(store.loadOverrides()[date])
        assertEquals(10L, store.loadSettings().generation)
    }

    @Test fun staleOriginalAndGenerationNeverCommitAnyBatchMember() = withStore { store, _ ->
        store.saveSettings(AppSettings(limitEnabled = false, generation = 3))
        val original = DateOverride(date, DatePolicy.MANUAL, 2)
        store.saveOverride(original)
        val edits = mapOf(date to original.copy(workTypeId = WorkTypes.DAY_SUBSTITUTE), date.plusDays(1) to DateOverride(date.plusDays(1), DatePolicy.EXCLUDE))
        assertThrows(IllegalStateException::class.java) { store.saveDateEdits(edits, 2, mapOf(date to original, date.plusDays(1) to null)) }
        assertThrows(IllegalStateException::class.java) { store.saveDateEdits(edits, 3, mapOf(date to null, date.plusDays(1) to null)) }
        assertEquals(mapOf(date to original), store.loadOverrides())
        assertEquals(3L, store.loadSettings().generation)
    }

    @Test fun dbCommitFailureRollsBackDatesLearnedDefaultsAndGeneration() = withStore { store, _ ->
        val settings = AppSettings(limitEnabled = false, generation = 4)
        store.saveSettings(settings)
        val original = DateOverride(date, DatePolicy.MANUAL, 2)
        store.saveOverride(original)
        store.writableDatabase.execSQL("CREATE TRIGGER block_config BEFORE INSERT ON config BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertThrows(RuntimeException::class.java) {
            store.saveDateEdits(mapOf(date to original.copy(policy = DatePolicy.EXCLUDE, workTypeId = WorkTypes.EDUCATION)),
                4, mapOf(date to original), mapOf(WorkTypes.EDUCATION to true))
        }
        assertEquals(settings, store.loadSettings())
        assertEquals(mapOf(date to original), store.loadOverrides())
    }

    @Test fun referencedCustomCanBeArchivedButNotDeletedAndNotNewlyAssigned() = withStore { store, _ ->
        val custom = WorkTypeDefinition(UUID.randomUUID().toString(), "추가근무", "추가", 0xFF006B8F, true, true, 13)
        store.saveSettings(AppSettings(limitEnabled = false, workTypes = WorkTypes.builtIns + custom))
        val original = DateOverride(date.minusYears(1), DatePolicy.MANUAL, workTypeId = custom.id)
        store.saveOverride(original)
        assertThrows(IllegalArgumentException::class.java) { store.saveWorkTypes(WorkTypes.builtIns, 0) }
        assertTrue(store.saveWorkTypes(WorkTypes.builtIns + custom.copy(archived = true), 0))
        assertEquals(original, store.loadOverrides()[original.date])
        assertThrows(IllegalArgumentException::class.java) {
            store.saveDateEdits(mapOf(date to DateOverride(date, DatePolicy.MANUAL, workTypeId = custom.id)), 1, mapOf(date to null))
        }
        assertTrue(store.saveDateEdits(mapOf(original.date to original.copy(quantity = 2)), 1, mapOf(original.date to original)))
    }

    @Test fun schema2RoundTripPreservesDefinitionsRelationsAndLedgerWithoutAuthorization() = withStore { store, _ ->
        val types = WorkTypes.builtIns.map { if (it.id == WorkTypes.EDUCATION) it.copy(mealDefault = true, name = "현장교육") else it }
        store.saveSettings(AppSettings(limitEnabled = false, masterEnabled = true, patternConfirmed = true, liveScope = LiveScope.RECURRING, workTypes = types))
        val original = DateOverride(date, DatePolicy.MANUAL, workTypeId = WorkTypes.EDUCATION)
        store.saveOverride(original)
        val record = ExecutionRecord(date, 1, ExecutionStatus.COMPLETED, "VERIFIED", "test", submissionPossible = true)
        store.record(record)
        val backup = store.exportPlanBackup()
        assertEquals(2, JsonParser.parseString(backup).asJsonObject.get("schema").asInt)
        store.importPlanBackup(backup)
        assertEquals(types, store.loadSettings().workTypes)
        assertEquals(original, store.loadOverrides()[date])
        assertEquals(listOf(record), store.loadRecords())
        assertFalse(store.loadSettings().masterEnabled)
        assertEquals(LiveScope.NONE, store.loadSettings().liveScope)
    }

    @Test fun schema1IgnoresInjectedNewFieldsAndSchema2RejectsMissingOrUnknownDefinitionsAtomically() = withStore { store, _ ->
        val settings = AppSettings(limitEnabled = false, generation = 7)
        store.saveSettings(settings)
        val original = DateOverride(date, DatePolicy.MANUAL, 2, workTypeId = WorkTypes.DAY_SUBSTITUTE)
        store.saveOverride(original)
        val source = store.exportPlanBackup()
        val missing = JsonParser.parseString(source).asJsonObject.apply { getAsJsonObject("settings").getAsJsonArray("workTypes").remove(0) }
        assertThrows(IllegalArgumentException::class.java) { store.importPlanBackup(missing.toString()) }
        val unknown = JsonParser.parseString(source).asJsonObject.apply { getAsJsonArray("overrides")[0].asJsonObject.addProperty("workTypeId", "missing-id") }
        assertThrows(IllegalArgumentException::class.java) { store.importPlanBackup(unknown.toString()) }
        assertEquals(settings, store.loadSettings())
        assertEquals(original, store.loadOverrides()[date])
        val legacy = JsonParser.parseString(source).asJsonObject.apply {
            addProperty("schema", 1)
            getAsJsonObject("settings").add("workTypes", com.google.gson.JsonArray())
            getAsJsonArray("overrides")[0].asJsonObject.addProperty("workTypeId", "missing-id")
        }
        store.importPlanBackup(legacy.toString())
        assertEquals(WorkTypes.builtIns, store.loadSettings().workTypes)
        assertEquals(original.copy(workTypeId = null), store.loadOverrides()[date])
    }

    @Test fun inconsistentAutoWorkPairCannotBeSavedOrImportedButExplicitMealIntentSurvives() = withStore { store, _ ->
        store.saveSettings(AppSettings(limitEnabled = false, generation = 3))
        val inconsistent = DateOverride(date, DatePolicy.AUTO, workTypeId = WorkTypes.VACATION)
        assertThrows(IllegalArgumentException::class.java) { store.saveDateEdits(mapOf(date to inconsistent), 3, mapOf(date to null)) }
        val backup = JsonParser.parseString(store.exportPlanBackup()).asJsonObject.apply {
            getAsJsonArray("overrides").add(AppJson.gson.toJsonTree(inconsistent))
        }
        assertThrows(IllegalArgumentException::class.java) { store.importPlanBackup(backup.toString()) }
        assertEquals(3L, store.loadSettings().generation)
        assertTrue(store.loadOverrides().isEmpty())
        assertTrue(store.saveDateEdits(mapOf(date to inconsistent.copy(policy = DatePolicy.MANUAL)), 3, mapOf(date to null)))
        assertEquals(DatePolicy.MANUAL, store.loadOverrides()[date]?.policy)
    }

    @Test fun schema2RejectsRawIntegerOverflowAndColorBoundsBeforeGsonNarrowing() = withStore { store, _ ->
        val settings = AppSettings(limitEnabled = false, generation = 11)
        store.saveSettings(settings)
        val original = DateOverride(date, DatePolicy.MANUAL, 2, workTypeId = WorkTypes.EDUCATION)
        store.saveOverride(original)
        val backup = store.exportPlanBackup()
        listOf("sortOrder" to 4294967296L, "sortOrder" to -4294967296L,
            "sortOrder" to 10001L, "color" to 0xFEFFFFFFL, "color" to 0x100000000L).forEach { (key, number) ->
            val malformed = JsonParser.parseString(backup).asJsonObject.apply {
                getAsJsonObject("settings").getAsJsonArray("workTypes")[0].asJsonObject.addProperty(key, number)
            }
            assertThrows(IllegalArgumentException::class.java) { store.importPlanBackup(malformed.toString()) }
            assertEquals(settings, store.loadSettings())
            assertEquals(mapOf(date to original), store.loadOverrides())
        }
    }
}
