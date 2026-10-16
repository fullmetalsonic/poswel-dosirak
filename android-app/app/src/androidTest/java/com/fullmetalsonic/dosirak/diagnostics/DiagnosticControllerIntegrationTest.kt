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
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Plain Application runner, isolated settings/database, injected update and diagnostics.
// No activity, engine, scheduler, network request, purchase or notification is invoked.
@RunWith(AndroidJUnit4::class)
class DiagnosticControllerIntegrationTest {
    @Suppress("DEPRECATION")
    @Test fun controllerToggleShareExportAndClearPreserveReservationsAndLedger() = exerciseExport(false)

    @Suppress("DEPRECATION")
    @Test fun controllerSharesAndSavesZipBytesAndRejectsSnapshotDeletedInPicker() = exerciseExport(true)

    @Suppress("DEPRECATION")
    private fun exerciseExport(zip: Boolean) {
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
        var requestedSnapshot: DiagnosticSnapshot? = null
        val updateRequests = AtomicInteger()
        var shared: Intent? = null
        lateinit var controller: AppController
        var controllerCreated = false
        val exportFile = File(File(base.cacheDir, "analysis-share").apply { mkdirs() }, "controller-export-test.${if (zip) "zip" else "txt"}")
        try {
            instrumentation.runOnMainSync {
                controller = AppController(context, runtime, requestExport = { fail("Plan export must not run") },
                    requestImport = { fail("Plan import must not run") },
                    requestDiagnosticExport = { requestedSnapshot = it; exportRequests.incrementAndGet() },
                    requestDiagnosticShare = { shared = it }, diagnostics = fake,
                    updateCheck = { updateRequests.incrementAndGet(); UpdateStatus(message = "isolated") })
                controllerCreated = true
            }
            await { !controller.state.value.busy }
            instrumentation.runOnMainSync { controller.onAction(UiAction.SetAnalysisEnabled(true)) }
            await { controller.state.value.diagnostics.enabled && !controller.state.value.diagnosticActionBusy }
            if (zip) {
                instrumentation.runOnMainSync { controller.onAction(UiAction.SetDetailAnalysisEnabled(true)) }
                await { controller.state.value.diagnostics.detailEnabled && !controller.state.value.diagnosticActionBusy }
                assertTrue(controller.state.value.diagnostics.detailAcknowledged)
            }
            fake.record(DiagnosticEvent(DiagnosticEventType.RUN_FINISHED, DiagnosticStage.VERIFICATION,
                DiagnosticResult.FAILED, DiagnosticCode.NETWORK, date = date, quantity = 1))
            await { controller.state.value.diagnostics.bytes > 0 }
            instrumentation.runOnMainSync { controller.onAction(UiAction.ShareAnalysis) }
            await { shared != null && !controller.state.value.diagnosticActionBusy }
            val send = shared!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            val uri = send.clipData!!.getItemAt(0).uri
            assertEquals(if (zip) "application/zip" else "text/plain", send.type)
            val sharedBytes = base.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            if (!zip) {
                assertTrue(sharedBytes.toString(Charsets.UTF_8).contains("RUN_FINISHED"))
                assertTrue(sharedBytes.toString(Charsets.UTF_8).contains("NETWORK"))
            }
            instrumentation.runOnMainSync { controller.onAction(UiAction.ExportAnalysis) }
            await { exportRequests.get() == 1 && !controller.state.value.diagnosticActionBusy }
            assertEquals(1, exportRequests.get())
            assertEquals(zip, requestedSnapshot!!.isZip)
            assertEquals(if (zip) "test.zip" else "test.txt", requestedSnapshot!!.suggestedFileName)
            val exportUri = FileProvider.getUriForFile(base, "${base.packageName}.analysis-files", exportFile)
            instrumentation.runOnMainSync { controller.exportDiagnosticsTo(exportUri) }
            await { controller.state.value.diagnosticMessage == "로그 파일을 저장했습니다." }
            assertArrayEquals(sharedBytes, exportFile.readBytes())
            if (zip) ZipFile(exportFile).use { archive ->
                assertNotNull(archive.getEntry("analysis.txt"))
                assertNotNull(archive.getEntry("detail/events.jsonl"))
                val body = archive.getInputStream(archive.getEntry("analysis.txt")).bufferedReader().use { it.readText() }
                assertTrue(body.contains("RUN_FINISHED"))
            }
            instrumentation.runOnMainSync { controller.onAction(UiAction.SetAnalysisEnabled(false)) }
            await { !controller.state.value.diagnostics.enabled && !controller.state.value.diagnosticActionBusy }
            assertFalse(controller.state.value.diagnostics.detailEnabled)
            assertTrue(controller.state.value.diagnostics.bytes > 0)
            // Freeze a second export, then delete its source while the picker is open.
            instrumentation.runOnMainSync { controller.onAction(UiAction.ExportAnalysis) }
            await { exportRequests.get() == 2 && !controller.state.value.diagnosticActionBusy }
            instrumentation.runOnMainSync { controller.onAction(UiAction.ClearAnalysis) }
            await { fake.clearCount == 1 && !controller.state.value.diagnosticActionBusy }
            assertEquals(0L, controller.state.value.diagnostics.bytes)
            instrumentation.runOnMainSync { controller.exportDiagnosticsTo(exportUri) }
            await { controller.state.value.diagnosticMessage == "저장할 로그가 없거나 파일을 열지 못했습니다." }
            assertArrayEquals("Deleted snapshot must not overwrite an existing destination", sharedBytes, exportFile.readBytes())
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
            fake.archiveFile.delete()
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
        private var eraseEpoch = 0L
        var clearCount = 0
        private val cache = File(context.cacheDir, "analysis-share").apply { mkdirs() }
        var cachedFile = File(cache, "controller-share-test.txt")
            private set
        val archiveFile = File(cache, "controller-source-test.zip")
        override fun record(event: DiagnosticEvent) {
            if (flow.value.enabled) {
                text = "schema=1 version=test\n${event.event} ${event.stage} ${event.result} ${event.code}\n"
                flow.value = flow.value.copy(bytes = text.toByteArray().size.toLong(), lastSavedEpochMillis = 123L)
            }
        }
        override suspend fun setEnabled(enabled: Boolean) {
            flow.value = flow.value.copy(enabled = enabled, detailEnabled = if (enabled) flow.value.detailEnabled else false)
        }
        override suspend fun setDetailEnabled(enabled: Boolean) {
            flow.value = flow.value.copy(detailEnabled = enabled && flow.value.enabled,
                detailAcknowledged = flow.value.detailAcknowledged || (enabled && flow.value.enabled), detailBytes = if (enabled) 20 else flow.value.detailBytes)
        }
        override suspend fun clear() {
            clearCount++; eraseEpoch++; text = ""; cachedFile.delete(); archiveFile.delete()
            flow.value = flow.value.copy(bytes = 0, detailBytes = 0, lastSavedEpochMillis = null)
        }
        override suspend fun snapshot(): DiagnosticSnapshot? {
            if (text.isEmpty()) return null
            if (flow.value.detailBytes <= 0) return DiagnosticSnapshot(text, "test.txt", eraseEpoch)
            if (!archiveFile.exists()) ZipOutputStream(archiveFile.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("analysis.txt")); zip.write(text.toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("detail/events.jsonl")); zip.write("{\"kind\":\"RUN_STARTED\"}\n".toByteArray()); zip.closeEntry()
            }
            return DiagnosticSnapshot("ZIP summary only", "test.zip", eraseEpoch, "application/zip", archiveFile)
        }
        override suspend fun cacheSnapshot(snapshot: DiagnosticSnapshot): File? {
            if (snapshot.eraseEpoch != eraseEpoch) return null
            cachedFile = File(cache, "controller-share-test.${if (snapshot.isZip) "zip" else "txt"}")
            if (snapshot.isZip) snapshot.archive!!.copyTo(cachedFile, overwrite = true) else cachedFile.writeText(snapshot.text)
            return cachedFile
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
