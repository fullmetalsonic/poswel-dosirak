package com.fullmetalsonic.dosirak.diagnostics

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.ZipFile
import kotlin.coroutines.CoroutineContext

class DetailStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Dispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Preferences : DiagnosticPreference {
        var basic = false; var detail = false; var acknowledged = false; var failWrite = false; var failRead = false
        override fun readEnabled(): Boolean { check(!failRead); return basic }
        override fun writeEnabled(enabled: Boolean): Boolean = writeModes(enabled, false)
        override fun readDetailEnabled() = detail
        override fun readDetailAcknowledged() = acknowledged
        override fun writeModes(enabled: Boolean, detailEnabled: Boolean): Boolean {
            if (failWrite) return false
            basic = enabled; detail = enabled && detailEnabled
            if (detail) acknowledged = true
            return true
        }
    }
    private inner class Fixture {
        val root = temporary.newFolder()
        val prefs = Preferences()
        val dispatcher = Dispatcher()
        val disk = DiagnosticDisk(File(root, "analysis"), File(root, "analysis-share"))
        var now = 100_000L
        val store = DiagnosticStore(disk, prefs, "1.1.1", { now }, { 123 }, dispatcher)
        fun <T> finish(block: suspend () -> T): T = runBlocking {
            withTimeout(5_000) {
                val result = async(start = CoroutineStart.UNDISPATCHED) { block() }
                while (!result.isCompleted) { dispatcher.drain(); yield() }
                result.await()
            }
        }
        fun enable() { finish { store.setEnabled(true); store.setDetailEnabled(true) } }
        fun capture(run: String = DiagnosticRun.newId()) = requireNotNull(store.captureDetail(run, DiagnosticRun.newId(), 1))
        fun idle() = finish { store.awaitIdle() }
    }
    private fun detail() = SafeDetailObservation(DetailKind.STRUCTURE, route = DiagnosticRoute.CHECKOUT,
        form = SafeFormStructure(DiagnosticRoute.SUBMISSION, DetailMethod.POST,
            listOf(SafeFormField(SafeFieldName.QTY, type = SafeFieldType.NUMBER, required = true, minimum = 1, maximum = 5))),
        business = SafeBusinessObservation(quantity = 3, total = 15000))
    private fun diagnosticGson(): Gson = GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java, JsonSerializer<LocalDate> { value, _, _ -> JsonPrimitive(value.toString()) })
        .registerTypeAdapter(LocalDate::class.java, JsonDeserializer<LocalDate> { value, _, _ -> LocalDate.parse(value.asString) })
        .create()

    @Test fun dependencyAcknowledgementRecreationAndFailClosedTransitions() {
        val f = Fixture()
        assertFalse(f.store.status.value.detailEnabled)
        f.finish { f.store.setDetailEnabled(true) }
        assertFalse(f.store.status.value.enabled)
        assertFalse(f.store.status.value.detailEnabled)
        f.enable()
        assertTrue(f.store.status.value.detailAcknowledged)
        assertTrue(f.prefs.acknowledged)
        val stale = f.capture()
        f.prefs.failWrite = true
        f.finish { f.store.setDetailEnabled(false) }
        assertFalse(f.store.isDetailCaptureCurrent(stale))
        assertFalse(f.store.status.value.detailEnabled)
        assertEquals(DiagnosticStorageError.SETTINGS_WRITE, f.store.status.value.error)
        f.prefs.failWrite = false
        f.finish { f.store.setEnabled(false); f.store.setEnabled(true) }
        assertFalse(f.store.status.value.detailEnabled)
        assertFalse(f.prefs.detail)
        assertTrue(f.prefs.acknowledged)
        f.store.close()
        val reopened = DiagnosticStore(f.disk, f.prefs, "1.1.1", { f.now }, { 1 }, f.dispatcher)
        f.finish { reopened.awaitIdle() }
        assertTrue(reopened.status.value.enabled)
        assertFalse(reopened.status.value.detailEnabled)
        assertTrue(reopened.status.value.detailAcknowledged)
        reopened.close()
    }

    @Test fun queueIsBoundedDetachedAndEpochStopsInFlightCapture() {
        val f = Fixture(); f.enable()
        val capture = f.capture()
        val options = mutableListOf(1, 2, 3)
        f.store.safeDetail(capture, detail().copy(business = SafeBusinessObservation(quantityOptions = options)))
        options.clear(); options += 9999
        repeat(40) { f.store.safeDetail(capture, detail()) }
        assertTrue(f.store.status.value.detailDropped > 0)
        f.idle()
        val persisted = f.disk.detailDirectory.listFiles().orEmpty().filter { it.extension == "jsonl" }.joinToString("") { it.readText() }
        assertTrue(persisted.contains("\"quantityOptions\":[1,2,3]"))
        assertFalse(persisted.contains("9999"))
        f.finish { f.store.setDetailEnabled(false) }
        val length = f.disk.detailDirectory.listFiles().orEmpty().sumOf { it.length() }
        f.store.safeDetail(capture, detail()); f.idle()
        assertEquals(length, f.disk.detailDirectory.listFiles().orEmpty().sumOf { it.length() })
        f.store.close()
    }

    @Test fun zipHasSanitizedSnapshotsCoverageChecksumsAndClearInvalidatesSharing() {
        val f = Fixture(); f.enable()
        val capture = f.capture()
        f.store.safeDetail(capture, detail())
        f.store.record(DiagnosticEvent(DiagnosticEventType.PLAN_CHECKED, quantity = 3))
        val snapshot = requireNotNull(f.finish { f.store.snapshot() })
        assertEquals("application/zip", snapshot.mimeType)
        val file = requireNotNull(f.finish { f.store.cacheSnapshot(snapshot) })
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertTrue(names.containsAll(listOf("manifest.json", "summary.txt", "basic.jsonl", "details.jsonl", "coverage.json", "checksums.sha256")))
            assertTrue(names.any { it.startsWith("snapshots/CHECKOUT/") })
            val allText = names.joinToString("") { zip.getInputStream(zip.getEntry(it)).bufferedReader().readText() }
            assertFalse(allText.contains("<html")); assertFalse(allText.contains("passwordValue"))
            val checksums = zip.getInputStream(zip.getEntry("checksums.sha256")).bufferedReader().readLines()
            checksums.forEach { line ->
                val (expected, name) = line.split("  ", limit = 2)
                assertNotEquals("checksums.sha256", name)
                val actual = MessageDigest.getInstance("SHA-256").digest(zip.getInputStream(zip.getEntry(name)).readBytes())
                    .joinToString("") { "%02x".format(it) }
                assertEquals(name, expected, actual)
            }
            assertEquals(names.size - 1, checksums.size)
        }
        f.finish { f.store.clear() }
        assertNull(f.finish { f.store.cacheSnapshot(snapshot) })
        assertFalse(file.exists())
        assertFalse(f.disk.detailDirectory.exists())
        val plain = requireNotNull(f.finish { f.store.snapshot() })
        assertEquals("text/plain", plain.mimeType)
        f.store.close()
    }

    @Test fun malformedCorrelationAndOversizedStructuresCannotEnterQueue() {
        val f = Fixture(); f.enable()
        assertNull(f.store.captureDetail("account-secret"))
        val capture = f.capture()
        f.store.safeDetail(capture, detail().copy(parentRunId = "token-secret"))
        f.store.safeDetail(capture, detail().copy(form = SafeFormStructure(fields = List(65) { SafeFormField(SafeFieldName.QTY) })))
        f.idle()
        assertEquals(2L, f.store.status.value.detailDropped)
        assertFalse(f.disk.detailDirectory.listFiles().orEmpty().any { it.extension == "jsonl" })
        f.store.close()
    }

    private fun saved(run: String, sequence: Long = 1) = SavedDetail(processInstanceId = DiagnosticRun.newId(), runId = run,
        requestId = DiagnosticRun.newId(), requestOrdinal = 1, parentRequestId = null, captureSegmentId = DiagnosticRun.newId(),
        sequence = sequence, epochMillis = 100_000, elapsedMillis = 1, observation = detail())

    @Test fun runCountSnapshotCountRetentionAndPerRunLimitsRotateWholeRecords() {
        val directory = temporary.newFolder()
        var now = 100_000L
        val journal = DetailJournal(directory, diagnosticGson(), { now }, limit = 100_000, runLimit = 20_000, runCount = 2, snapshotCount = 3)
        val first = DiagnosticRun.newId()
        assertTrue(journal.append(saved(first)))
        repeat(3) { assertTrue(journal.append(saved(DiagnosticRun.newId()))) }
        assertFalse(File(directory, "$first.jsonl").exists())
        assertTrue(directory.listFiles().orEmpty().count { it.extension == "jsonl" } <= 2)
        val active = DiagnosticRun.newId()
        repeat(10) { journal.append(saved(active, it + 1L)) }
        assertTrue(journal.dropped > 0)
        assertTrue(File(directory, "$active.jsonl").length() <= 20_000)
        now += DiagnosticStore.DEFAULT_RETENTION_MILLIS + 1
        journal.refresh()
        assertFalse(journal.hasDetails)
    }

    @Test fun restartCanonicalizesUnknownFieldsAndDropsCorruptionAndUnsupportedSchema() {
        val directory = temporary.newFolder()
        val run = DiagnosticRun.newId()
        val gson = diagnosticGson()
        val good = gson.toJsonTree(saved(run)).asJsonObject.apply { addProperty("rawResponse", "secret-marker") }
        val unsupported = gson.toJsonTree(saved(run)).asJsonObject.apply { addProperty("detailSchemaVersion", 9) }
        val file = File(directory, "$run.jsonl")
        file.writeText("$good\n{broken secret-marker\n$unsupported\n")
        val journal = DetailJournal(directory, gson, { 100_000 })
        journal.refresh()
        assertTrue(journal.hasDetails)
        assertFalse(file.readText().contains("secret-marker"))
        assertEquals(1, file.readLines().size)
        assertEquals(2L, journal.corrupt)
        val firstCount = journal.corrupt
        journal.refresh()
        assertEquals(firstCount, journal.corrupt)
    }

    @Test fun invalidatedArchiveCannotPublishCompleteOrPendingFile() {
        val directory = temporary.newFolder()
        val journal = DetailJournal(directory, diagnosticGson(), { 100_000 })
        journal.append(saved(DiagnosticRun.newId()))
        val archive = File(temporary.newFolder(), "export.zip")
        var checks = 0
        assertTrue(runCatching { journal.archive(archive, "1.1.1", "", "", 0, 0) { ++checks < 4 } }.isFailure)
        assertFalse(archive.exists())
        assertFalse(File(archive.parentFile, "export.zip.pending").exists())
    }

    @Test fun repeatedSnapshotsKeepOnePrivateArchiveAndOneShareCache() {
        val f = Fixture(); f.enable()
        f.store.safeDetail(f.capture(), detail())
        var prior: DiagnosticSnapshot? = null
        repeat(3) {
            val current = requireNotNull(f.finish { f.store.snapshot() })
            if (prior != null) assertNull(f.finish { f.store.cacheSnapshot(requireNotNull(prior)) })
            f.finish { f.store.cacheSnapshot(current) }
            assertEquals(1, f.disk.archiveDirectory.listFiles().orEmpty().size)
            assertEquals(1, File(f.root, "analysis-share").listFiles().orEmpty().size)
            prior = current
        }
        File(f.root, "analysis-share").listFiles().orEmpty().forEach { it.setLastModified(System.currentTimeMillis() - 25L * 60 * 60 * 1000) }
        f.disk.pruneExpiredArtifacts()
        assertTrue(File(f.root, "analysis-share").listFiles().orEmpty().isEmpty())
        f.store.close()
    }

    @Test fun requestCoverageKeepsPartialStructureDespiteLaterCompleteBusiness() {
        val journal = DetailJournal(temporary.newFolder(), diagnosticGson(), { 100_000 })
        val first = saved(DiagnosticRun.newId())
        journal.append(first.copy(observation = SafeDetailObservation(DetailKind.HTTP, route = DiagnosticRoute.CHECKOUT,
            coverage = DetailCoverage.HTTP_ONLY, omissions = setOf(DetailOmission.STRUCTURE_NOT_EXTRACTED))))
        journal.append(first.copy(sequence = 2, observation = detail().copy(coverage = DetailCoverage.PARTIAL, omissions = setOf(DetailOmission.FIELD_LIMIT))))
        journal.append(first.copy(sequence = 3, observation = SafeDetailObservation(DetailKind.BUSINESS, route = DiagnosticRoute.CHECKOUT)))
        journal.append(first.copy(sequence = 4, requestId = null, observation = SafeDetailObservation(DetailKind.RUN_FINISHED)))
        val archive = File(temporary.newFolder(), "coverage.zip")
        journal.archive(archive, "1.1.1", "", "", 0, 0) { true }
        ZipFile(archive).use { zip ->
            val gson = diagnosticGson()
            val coverage = gson.fromJson(zip.getInputStream(zip.getEntry("coverage.json")).bufferedReader(), com.google.gson.JsonObject::class.java)
            val checkout = coverage.getAsJsonArray("runs")[0].asJsonObject.getAsJsonArray("routes")
                .first { it.asJsonObject.get("route").asString == "CHECKOUT" }.asJsonObject
            assertEquals("PARTIAL", checkout.get("coverage").asString)
            val request = checkout.getAsJsonArray("requests")[0].asJsonObject
            assertEquals("PARTIAL", request.get("coverage").asString)
            assertFalse(request.getAsJsonArray("omissions").toString().contains("STRUCTURE_NOT_EXTRACTED"))
            assertTrue(request.getAsJsonArray("omissions").toString().contains("FIELD_LIMIT"))
            val manifest = gson.fromJson(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader(), com.google.gson.JsonObject::class.java)
            assertFalse(manifest.get("complete").asBoolean)
        }
    }

    @Test fun fingerprintUsesOnlySafeStructureAndStorageFailureRecoversAfterClear() {
        val directory = temporary.newFolder()
        val gson = diagnosticGson()
        val journal = DetailJournal(directory, gson, { 100_000 })
        val first = saved(DiagnosticRun.newId())
        journal.append(first)
        journal.append(saved(first.runId, 2).copy(observation = detail().copy(business = SafeBusinessObservation(quantity = 5))))
        val lines = File(directory, "${first.runId}.jsonl").readLines().map { gson.fromJson(it, com.google.gson.JsonObject::class.java) }
        assertEquals(lines[0].get("structureFingerprint"), lines[1].get("structureFingerprint"))
        assertTrue(lines[0].get("structureFingerprint").asString.matches(Regex("[a-f0-9]{64}")))
        val f = Fixture(); f.enable()
        f.disk.detailDirectory.writeText("blocked")
        f.store.safeDetail(f.capture(), detail()); f.idle()
        assertEquals(DiagnosticStorageError.WRITE, f.store.status.value.error)
        assertTrue(f.store.status.value.detailDropped > 0)
        f.finish { f.store.clear() }
        f.store.safeDetail(f.capture(), detail()); f.idle()
        assertTrue(f.store.status.value.detailBytes > 0)
        assertNull(f.store.status.value.error)
        f.store.close()
    }

    @Test fun jsonParseFailureKeepsReachedNotExtractedAndItsOmission() {
        val journal = DetailJournal(temporary.newFolder(), diagnosticGson(), { 100_000 })
        val first = saved(DiagnosticRun.newId())
        journal.append(first.copy(observation = SafeDetailObservation(DetailKind.HTTP, route = DiagnosticRoute.TEMPORARY_ORDER,
            coverage = DetailCoverage.HTTP_ONLY, omissions = setOf(DetailOmission.STRUCTURE_NOT_EXTRACTED))))
        journal.append(first.copy(sequence = 2, observation = SafeDetailObservation(DetailKind.STRUCTURE,
            route = DiagnosticRoute.TEMPORARY_ORDER, coverage = DetailCoverage.REACHED_NOT_EXTRACTED,
            code = DiagnosticCode.JSON_CONTRACT, omissions = setOf(DetailOmission.STRUCTURE_NOT_EXTRACTED, DetailOmission.MESSAGE_OMITTED))))
        val archive = File(temporary.newFolder(), "parse-failure.zip")
        journal.archive(archive, "1.1.1", "", "", 0, 0) { true }
        ZipFile(archive).use { zip ->
            val coverage = diagnosticGson().fromJson(zip.getInputStream(zip.getEntry("coverage.json")).bufferedReader(), com.google.gson.JsonObject::class.java)
            val route = coverage.getAsJsonArray("runs")[0].asJsonObject.getAsJsonArray("routes")
                .first { it.asJsonObject.get("route").asString == "TEMPORARY_ORDER" }.asJsonObject
            val request = route.getAsJsonArray("requests")[0].asJsonObject
            assertEquals("REACHED_NOT_EXTRACTED", request.get("coverage").asString)
            assertTrue(request.getAsJsonArray("omissions").toString().contains("STRUCTURE_NOT_EXTRACTED"))
            assertTrue(request.getAsJsonArray("omissions").toString().contains("MESSAGE_OMITTED"))
            assertTrue(zip.getInputStream(zip.getEntry("details.jsonl")).bufferedReader().readText().contains("JSON_CONTRACT"))
        }
    }
}
