package com.fullmetalsonic.dosirak.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.AppController
import com.fullmetalsonic.dosirak.diagnostics.*
import com.fullmetalsonic.dosirak.domain.*
import com.fullmetalsonic.dosirak.platform.RegistrationCode
import com.fullmetalsonic.dosirak.platform.RegistrationResult
import com.fullmetalsonic.dosirak.runtime.AppRuntime
import com.fullmetalsonic.dosirak.update.UpdateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.Collections
import java.util.UUID

// Isolated database/preferences and injected registration: no real alarms, engine run or site request.
@RunWith(AndroidJUnit4::class)
class CalendarControllerIntegrationTest {
    private val day = LocalDate.of(2026, 10, 20)

    @Test fun rebookRequiresExplicitConfirmationAndKeepsCalendarResultsSeparate() = fixture { f ->
        val original = DateOverride(day, DatePolicy.EXCLUDE, workTypeId = WorkTypes.VACATION)
        f.runtime.store.saveOverride(original)
        val ledger = ExecutionRecord(day, 1, ExecutionStatus.COMPLETED, "VERIFIED", "test", amount = 5000)
        f.runtime.store.record(ledger)
        f.send(UiAction.ArmRebook(day, 1, LocalTime.of(6, 32), 10, 0, confirmed = false, requestId = 201))
        f.awaitResult { f.controller.state.value.rebookResult?.requestId == 201L && !f.controller.state.value.busy }
        assertFalse(f.controller.state.value.rebookResult!!.success)
        assertNull(f.controller.state.value.dateSaveResult)
        assertEquals(original, f.runtime.store.loadOverrides()[day])
        assertEquals(listOf(ledger), f.runtime.store.loadRecords())
        assertEquals(10L, f.runtime.store.loadSettings().generation)
        assertEquals(0, f.registrations)
    }

    @Test fun busyEngineRejectsRebookWithoutMutatingOrRegistering() = fixture { f ->
        f.active(true)
        f.awaitResult { f.controller.state.value.busy }
        f.send(UiAction.ArmRebook(day, 1, LocalTime.of(6, 32), 10, 0, confirmed = true, requestId = 202))
        f.awaitResult { f.controller.state.value.rebookResult?.requestId == 202L }
        assertFalse(f.controller.state.value.rebookResult!!.success)
        assertEquals(PlanSaveOutcome.NOT_SAVED, f.controller.state.value.rebookResult!!.outcome)
        assertTrue(f.runtime.store.loadOverrides().isEmpty())
        assertTrue(f.runtime.store.loadRecords().isEmpty())
        assertEquals(10L, f.runtime.store.loadSettings().generation)
        assertEquals(0, f.registrations)
        f.active(false)
    }

    @Test fun dateBatchCommitsOneGenerationWithoutTouchingOrdersOrActivation() = fixture { f ->
        val order = ExecutionRecord(day.minusDays(1), 1, ExecutionStatus.COMPLETED, "CONFIRMED", "test", amount = 5000)
        f.runtime.store.record(order)
        val value = DateOverride(day, DatePolicy.MANUAL, 2, LocalTime.of(6, 2), workTypeId = WorkTypes.DAY_SUBSTITUTE)
        f.send(UiAction.SaveDateBatch(mapOf(day to value, day.plusDays(1) to value.copy(date = day.plusDays(1))),
            10, mapOf(day to null, day.plusDays(1) to null), requestId = 101))
        f.awaitDate(101)
        assertEquals(PlanSaveOutcome.SAVED, f.controller.state.value.dateSaveResult?.outcome)
        assertEquals(11L, f.runtime.store.loadSettings().generation)
        assertFalse(f.runtime.store.loadSettings().masterEnabled)
        assertEquals(LiveScope.NONE, f.runtime.store.loadSettings().liveScope)
        assertEquals(value, f.runtime.store.loadOverrides()[day])
        assertEquals(listOf(order), f.runtime.store.loadRecords())
        assertEquals(1, f.registrations)
        assertFalse(f.runtime.engine.active.value)
    }

    @Test fun unchangedAndStaleSavesDoNotRegisterOrChangeGeneration() = fixture { f ->
        val value = DateOverride(day, DatePolicy.EXCLUDE, workTypeId = WorkTypes.VACATION)
        f.runtime.store.saveOverride(value)
        f.send(UiAction.SaveDateBatch(mapOf(day to value), 10, mapOf(day to value), requestId = 102))
        f.awaitDate(102)
        assertEquals(PlanSaveOutcome.UNCHANGED, f.controller.state.value.dateSaveResult?.outcome)
        assertEquals(0, f.registrations)
        f.send(UiAction.SaveDateBatch(mapOf(day to value.copy(policy = DatePolicy.MANUAL)),
            9, mapOf(day to value), requestId = 103))
        f.awaitDate(103)
        assertEquals(PlanSaveOutcome.CONFLICT, f.controller.state.value.dateSaveResult?.outcome)
        assertFalse(f.controller.state.value.dateSaveResult!!.success)
        assertEquals(10L, f.runtime.store.loadSettings().generation)
        assertEquals(value, f.runtime.store.loadOverrides()[day])
        assertEquals(0, f.registrations)
    }

    @Test fun committedScheduleBlockedFailedAndExceptionStaySavedWithoutAnotherCommit() = fixture { f ->
        listOf(RegistrationCode.BLOCKED, RegistrationCode.FAILED, null).forEachIndexed { index, code ->
            f.nextRegistration = code
            val target = day.plusDays(index.toLong())
            val value = DateOverride(target, DatePolicy.MANUAL, 1, workTypeId = WorkTypes.DAY_SUBSTITUTE)
            val generation = f.runtime.store.loadSettings().generation
            f.send(UiAction.SaveDateBatch(mapOf(target to value), generation, mapOf(target to null), requestId = 110L + index))
            f.awaitDate(110L + index)
            assertTrue(f.controller.state.value.dateSaveResult!!.success)
            assertEquals(PlanSaveOutcome.SAVED_REGISTRATION_PROBLEM, f.controller.state.value.dateSaveResult?.outcome)
            assertTrue(f.controller.state.value.registrationProblem)
            assertEquals(value, f.controller.state.value.overrides[target])
            assertEquals(generation + 1, f.runtime.store.loadSettings().generation)
        }
        assertEquals(3, f.registrations)
    }

    @Test fun databaseWriteFailureIsNotReportedAsConflictOrSaved() = fixture { f ->
        f.runtime.store.writableDatabase.execSQL(
            "CREATE TRIGGER fail_calendar_insert BEFORE INSERT ON exceptions BEGIN SELECT RAISE(IGNORE); END")
        val value = DateOverride(day, DatePolicy.MANUAL, workTypeId = WorkTypes.DAY_SUBSTITUTE)
        f.send(UiAction.SaveDateBatch(mapOf(day to value), 10, mapOf(day to null), requestId = 115))
        f.awaitDate(115)
        assertFalse(f.controller.state.value.dateSaveResult!!.success)
        assertEquals(PlanSaveOutcome.NOT_SAVED, f.controller.state.value.dateSaveResult?.outcome)
        assertEquals(10L, f.runtime.store.loadSettings().generation)
        assertTrue(f.runtime.store.loadOverrides().isEmpty())
        assertEquals(0, f.registrations)
    }

    @Test fun activeCancellationPreservesWorkChangeAndLedgerWhileMixedEnableIsRejected() = fixture { f ->
        val original = DateOverride(day, DatePolicy.MANUAL, 3, LocalTime.of(6, 1), workTypeId = WorkTypes.DAY_SUBSTITUTE)
        f.runtime.store.saveOverride(original)
        val ledger = ExecutionRecord(day, 3, ExecutionStatus.NEEDS_CHECK, "SUBMIT_INTENT", "test", submissionPossible = true)
        f.runtime.store.record(ledger)
        f.active(true)
        val vacation = original.copy(policy = DatePolicy.EXCLUDE, workTypeId = WorkTypes.VACATION)
        f.send(UiAction.SaveDateBatch(mapOf(day to vacation, day.plusDays(1) to original.copy(date = day.plusDays(1))),
            10, mapOf(day to original, day.plusDays(1) to null), requestId = 120))
        f.awaitResult { f.controller.state.value.dateSaveResult?.requestId == 120L }
        assertFalse(f.controller.state.value.dateSaveResult!!.success)
        assertEquals(original, f.runtime.store.loadOverrides()[day])
        assertEquals(10L, f.runtime.store.loadSettings().generation)
        assertEquals(0, f.registrations)
        f.send(UiAction.SaveDateBatch(mapOf(day to vacation), 10, mapOf(day to original), requestId = 121))
        f.awaitResult { f.controller.state.value.dateSaveResult?.requestId == 121L }
        assertTrue(f.controller.state.value.dateSaveResult!!.success)
        assertEquals(vacation, f.runtime.store.loadOverrides()[day])
        assertEquals(listOf(ledger), f.runtime.store.loadRecords())
        assertEquals(11L, f.runtime.store.loadSettings().generation)
        assertEquals(1, f.registrations)
        f.active(false)
    }

    @Test fun failedActiveCasDoesNotInvalidateOtherPendingEdits() = fixture { f ->
        val original = DateOverride(day, DatePolicy.MANUAL, workTypeId = WorkTypes.DAY)
        f.runtime.store.saveOverride(original)
        val sequenceBefore = f.stopSequence()
        f.active(true)
        f.send(UiAction.SaveDateBatch(mapOf(day to original.copy(policy = DatePolicy.EXCLUDE, workTypeId = WorkTypes.VACATION)),
            9, mapOf(day to original), requestId = 130))
        f.awaitResult { f.controller.state.value.dateSaveResult?.requestId == 130L }
        assertEquals(PlanSaveOutcome.CONFLICT, f.controller.state.value.dateSaveResult?.outcome)
        assertEquals(sequenceBefore, f.stopSequence())
        assertEquals(0, f.registrations)
        assertEquals(original, f.runtime.store.loadOverrides()[day])
        f.active(false)
    }

    @Test fun catalogSaveAndLearnedDefaultAreIndependentFromExistingDatePolicy() = fixture { f ->
        val existing = DateOverride(day, DatePolicy.EXCLUDE, workTypeId = WorkTypes.EDUCATION)
        f.runtime.store.saveOverride(existing)
        val catalog = f.runtime.store.loadSettings().workTypes.map {
            if (it.id == WorkTypes.DAY_SUBSTITUTE) it.copy(name = "주간 지원", calendarLabel = "주지") else it
        }
        f.send(UiAction.SaveWorkTypes(catalog, 10, 140))
        f.awaitResult { f.controller.state.value.workTypesSaveResult?.requestId == 140L && !f.controller.state.value.busy }
        assertTrue(f.controller.state.value.workTypesSaveResult!!.success)
        assertEquals(existing, f.runtime.store.loadOverrides()[day])
        val education = DateOverride(day.plusDays(1), DatePolicy.MANUAL, 2, workTypeId = WorkTypes.EDUCATION)
        f.send(UiAction.SaveDateBatch(mapOf(education.date to education), 11, mapOf(education.date to null),
            mapOf(WorkTypes.EDUCATION to true), 141))
        f.awaitDate(141)
        assertEquals(true, f.runtime.store.loadSettings().workTypes.first { it.id == WorkTypes.EDUCATION }.mealDefault)
        assertEquals(existing, f.runtime.store.loadOverrides()[day])
        assertEquals(education, f.runtime.store.loadOverrides()[education.date])
        assertEquals(12L, f.runtime.store.loadSettings().generation)
        assertEquals(2, f.registrations)
        f.send(UiAction.SaveSettings(AppSettings(defaultQuantity = 4, limitEnabled = false), 12, 142))
        f.awaitResult { f.controller.state.value.settingsSaveResult?.requestId == 142L && !f.controller.state.value.busy }
        assertEquals("주지", f.runtime.store.loadSettings().workTypes.first { it.id == WorkTypes.DAY_SUBSTITUTE }.calendarLabel)
        assertEquals(existing, f.runtime.store.loadOverrides()[day])
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }

    private class Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val prefix = "calendar-controller-${UUID.randomUUID()}-"
        val names = Collections.synchronizedSet(mutableSetOf<String>())
        val context = IsolatedContext(base, prefix, names)
        private val diagnostics = object : DiagnosticController {
            override val status = MutableStateFlow(DiagnosticStatus())
            override fun record(event: DiagnosticEvent) {}
            override suspend fun setEnabled(enabled: Boolean) {}
            override suspend fun clear() {}
            override suspend fun snapshot(): DiagnosticSnapshot? = null
            override suspend fun cacheSnapshot(snapshot: DiagnosticSnapshot): File? = null
        }
        val runtime = AppRuntime(context, diagnostics)
        @Volatile var registrations = 0
        @Volatile var nextRegistration: RegistrationCode? = RegistrationCode.STOPPED
        lateinit var controller: AppController
        init {
            runtime.store.saveSettings(AppSettings(generation = 10, limitEnabled = false))
            instrumentation.runOnMainSync {
                controller = AppController(context, runtime, {}, {}, diagnostics = diagnostics,
                    updateCheck = { UpdateStatus(message = "isolated") }, registrationUpdate = {
                        registrations++
                        RegistrationResult(nextRegistration ?: error("injected registration failure"), "isolated")
                    })
            }
            awaitResult { !controller.state.value.busy }
        }
        fun send(action: UiAction) = instrumentation.runOnMainSync { controller.onAction(action) }
        fun awaitDate(requestId: Long) = awaitResult { controller.state.value.dateSaveResult?.requestId == requestId && !controller.state.value.busy }
        fun awaitResult(check: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!check() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("Calendar controller action timed out", check())
        }
        @Suppress("UNCHECKED_CAST")
        fun active(value: Boolean) = instrumentation.runOnMainSync {
            val field = runtime.engine.javaClass.getDeclaredField("mutableActive").apply { isAccessible = true }
            (field.get(runtime.engine) as MutableStateFlow<Boolean>).value = value
        }
        fun stopSequence(): Long {
            val field = controller.javaClass.getDeclaredField("stopSequence").apply { isAccessible = true }
            return (field.get(controller) as java.util.concurrent.atomic.AtomicLong).get()
        }
        fun close() {
            instrumentation.runOnMainSync { controller.close() }
            runtime.store.close()
            base.deleteDatabase(prefix + "reservations.db")
            names.toList().forEach {
                base.deleteSharedPreferences(it)
                base.createDeviceProtectedStorageContext().deleteSharedPreferences(it)
            }
        }
    }

    private class IsolatedContext(base: Context, private val prefix: String, private val names: MutableSet<String>) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names.add(prefix + name)
            return super.getSharedPreferences(prefix + name, mode)
        }
        override fun getDatabasePath(name: String): File = super.getDatabasePath(prefix + name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            super.openOrCreateDatabase(prefix + name, mode, factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            super.openOrCreateDatabase(prefix + name, mode, factory, errorHandler)
        override fun createDeviceProtectedStorageContext(): Context = IsolatedContext(super.createDeviceProtectedStorageContext(), prefix, names)
    }
}
