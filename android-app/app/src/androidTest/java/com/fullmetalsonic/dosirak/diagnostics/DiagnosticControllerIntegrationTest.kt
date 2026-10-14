package com.fullmetalsonic.dosirak.diagnostics

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.AppController
import com.fullmetalsonic.dosirak.domain.*
import com.fullmetalsonic.dosirak.runtime.AppRuntime
import com.fullmetalsonic.dosirak.ui.UiAction
import com.fullmetalsonic.dosirak.update.UpdateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

// Plain Application runner, isolated settings/database, injected update and diagnostics.
// No activity, engine, scheduler, network request, purchase or notification is invoked.
@RunWith(AndroidJUnit4::class)
class DiagnosticControllerIntegrationTest {
    @Suppress("DEPRECATION")
    @Test fun controllerToggleShareExportAndClearPreserveReservationsAndLedger() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val names = Collections.synchronizedSet(mutableSetOf<String>())
        val prefix = "analysis-controller-${UUID.randomUUID()}-"
        val context = IsolatedContext(base, prefix, names)
        val fake = FakeDiagnostics(base)
        val runtime = AppRuntime(context, fake)
        val original = AppSettings(masterEnabled = true, generation = 71, accountGeneration = 12)
        val date = LocalDate.of(2026, 10, 13)
        val override = DateOverride(date.plusDays(1), DatePolicy.EXCLUDE)
        val ledger = ExecutionRecord(date, 1, ExecutionStatus.NEEDS_CHECK, "SUBMIT_INTENT", "Preserve ledger",
            submissionPossible = true, generation = 71, accountGeneration = 12)
        runtime.store.saveSettings(original)
        runtime.store.saveOverride(override)
        runtime.store.record(ledger)
        val exportRequests = AtomicInteger()
        val updateRequests = AtomicInteger()
        var shared: Intent? = null
        lateinit var controller: AppController
        var controllerCreated = false
        val exportFile = File(File(base.cacheDir, "analysis-share").apply { mkdirs() }, "controller-export-test.txt")
        try {
            instrumentation.runOnMainSync {
                controller = AppController(context, runtime, requestExport = { fail("Plan export must not run") },
                    requestImport = { fail("Plan import must not run") },
                    requestDiagnosticExport = { exportRequests.incrementAndGet() },
                    requestDiagnosticShare = { shared = it }, diagnostics = fake,
                    updateCheck = { updateRequests.incrementAndGet(); UpdateStatus(message = "isolated") })
                controllerCreated = true
            }
            await { !controller.state.value.busy }
            instrumentation.runOnMainSync { controller.onAction(UiAction.SetAnalysisEnabled(true)) }
            await { controller.state.value.diagnostics.enabled && !controller.state.value.diagnosticActionBusy }
            fake.record(DiagnosticEvent(DiagnosticEventType.RUN_FINISHED, DiagnosticStage.VERIFICATION,
                DiagnosticResult.FAILED, DiagnosticCode.NETWORK, date = date, quantity = 1))
            await { controller.state.value.diagnostics.bytes > 0 }
            instrumentation.runOnMainSync { controller.onAction(UiAction.ShareAnalysis) }
            await { shared != null && !controller.state.value.diagnosticActionBusy }
            val send = shared!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            val uri = send.clipData!!.getItemAt(0).uri
            val sharedText = base.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            assertTrue(sharedText.contains("RUN_FINISHED"))
            assertTrue(sharedText.contains("NETWORK"))
            instrumentation.runOnMainSync { controller.onAction(UiAction.ExportAnalysis) }
            assertEquals(1, exportRequests.get())
            val exportUri = FileProvider.getUriForFile(base, "${base.packageName}.analysis-files", exportFile)
            instrumentation.runOnMainSync { controller.exportDiagnosticsTo(exportUri) }
            await { controller.state.value.diagnosticMessage == "로그 파일을 저장했습니다." }
            assertEquals(sharedText, exportFile.readText())
            instrumentation.runOnMainSync { controller.onAction(UiAction.SetAnalysisEnabled(false)) }
            await { !controller.state.value.diagnostics.enabled && !controller.state.value.diagnosticActionBusy }
            assertTrue(controller.state.value.diagnostics.bytes > 0)
            instrumentation.runOnMainSync { controller.onAction(UiAction.ClearAnalysis) }
            await { fake.clearCount == 1 && !controller.state.value.diagnosticActionBusy }
            assertEquals(0L, controller.state.value.diagnostics.bytes)
            assertEquals(original, runtime.store.loadSettings())
            assertEquals(mapOf(override.date to override), runtime.store.loadOverrides())
            assertEquals(listOf(ledger), runtime.store.loadRecords())
            assertEquals(1, updateRequests.get())
            assertFalse(runtime.engine.active.value)
        } finally {
            if (controllerCreated) instrumentation.runOnMainSync { controller.close() }
            runtime.store.close()
            base.deleteDatabase(prefix + "reservations.db")
            names.toList().forEach { name ->
                base.deleteSharedPreferences(name)
                base.createDeviceProtectedStorageContext().deleteSharedPreferences(name)
            }
            fake.cachedFile.delete()
            exportFile.delete()
        }
    }

    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!check() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Controller action timed out", check())
    }

    private class FakeDiagnostics(context: Context) : DiagnosticController {
        private val flow = MutableStateFlow(DiagnosticStatus())
        override val status: StateFlow<DiagnosticStatus> = flow
        private var text = ""
        var clearCount = 0
        val cachedFile = File(File(context.cacheDir, "analysis-share").apply { mkdirs() }, "controller-share-test.txt")
        override fun record(event: DiagnosticEvent) {
            if (flow.value.enabled) {
                text = "schema=1 version=test\n${event.event} ${event.stage} ${event.result} ${event.code}\n"
                flow.value = flow.value.copy(bytes = text.toByteArray().size.toLong(), lastSavedEpochMillis = 123L)
            }
        }
        override suspend fun setEnabled(enabled: Boolean) { flow.value = flow.value.copy(enabled = enabled) }
        override suspend fun clear() { clearCount++; text = ""; cachedFile.delete(); flow.value = flow.value.copy(bytes = 0, lastSavedEpochMillis = null) }
        override suspend fun snapshot(): DiagnosticSnapshot? = text.takeIf { it.isNotEmpty() }?.let { DiagnosticSnapshot(it, "test.txt", 0) }
        override suspend fun cacheSnapshot(snapshot: DiagnosticSnapshot): File = cachedFile.apply { writeText(snapshot.text) }
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
