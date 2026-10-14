package com.fullmetalsonic.dosirak.diagnostics

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

class DiagnosticStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private class ManualDispatcher : CoroutineDispatcher() {
        private val work = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
        fun runAll() { while (work.isNotEmpty()) work.removeFirst().run() }
    }
    private class Preferences(var enabled: Boolean = false) : DiagnosticPreference {
        var lastSaved: Long? = null
        var fail = false
        override fun readEnabled() = enabled
        override fun writeEnabled(enabled: Boolean): Boolean { if (fail) return false; this.enabled = enabled; return true }
        override fun readLastSaved() = lastSaved
        override fun writeLastSaved(epochMillis: Long?): Boolean { if (fail) return false; lastSaved = epochMillis; return true }
    }
    private class Fixture(val root: File, val preferences: Preferences = Preferences(), val byteLimit: Int = 4096) {
        var now = 1000L
        val dispatcher = ManualDispatcher()
        val directory = File(root, "analysis")
        val cache = File(root, "analysis-share")
        val log get() = File(directory, "events.jsonl")
        fun create(disk: DiagnosticDisk = DiagnosticDisk(directory, cache)) = DiagnosticStore(disk, preferences, "1.0.2",
            wallMillis = { now }, elapsedMillis = { 10 }, dispatcher = dispatcher, byteLimit = byteLimit)
        fun <T> finish(block: suspend () -> T): T = runBlocking {
            val operation = async(start = CoroutineStart.UNDISPATCHED) { block() }
            dispatcher.runAll()
            operation.await()
        }
    }
    private fun fixture(enabled: Boolean = false, byteLimit: Int = 4096) = Fixture(temporary.newFolder(), Preferences(enabled), byteLimit)
    private fun event(quantity: Int = 1) = DiagnosticEvent(DiagnosticEventType.PLAN_CHECKED, DiagnosticStage.PREPARATION,
        DiagnosticResult.SUCCEEDED, quantity = quantity)

    @Test fun defaultOffDoesNotWriteAndPreferenceSurvivesRecreationWithoutTouchingOtherFiles() {
        val f = fixture()
        val unrelated = File(f.root, "reservations.db").apply { writeText("unchanged") }
        val store = f.create()
        store.record(event())
        f.finish { store.awaitIdle() }
        assertFalse(store.status.value.enabled)
        assertFalse(f.log.exists())
        f.finish { store.setEnabled(true) }
        store.record(event())
        f.now = 2000
        f.finish { store.awaitIdle() }
        assertEquals(2000L, store.status.value.lastSavedEpochMillis)
        val before = f.log.readText()
        f.finish { store.setEnabled(false) }
        store.record(event(2))
        f.finish { store.awaitIdle() }
        assertEquals(before, f.log.readText())
        store.close(); f.dispatcher.runAll()
        val reopened = f.create()
        f.finish { reopened.awaitIdle() }
        assertFalse(reopened.status.value.enabled)
        assertEquals(2000L, reopened.status.value.lastSavedEpochMillis)
        assertEquals("unchanged", unrelated.readText())
        reopened.close()
    }

    @Test fun queueIsBoundedAndOffDropsAllWaitingRecords() {
        val f = fixture(true)
        val store = f.create()
        repeat(129) { store.record(event()) }
        assertEquals(1L, store.status.value.dropped)
        assertEquals(DiagnosticStorageError.QUEUE_FULL, store.status.value.error)
        f.finish { store.setEnabled(false) }
        assertEquals(129L, store.status.value.dropped)
        assertFalse(f.log.exists())
        store.close()
    }

    @Test fun clearInvalidatesPendingRecordsAndOldSnapshotsAndDeletesShareCache() {
        val f = fixture(true)
        lateinit var store: DiagnosticStore
        store = f.create(object : DiagnosticDisk(f.directory, f.cache) {
            override fun clear() { store.record(event(4)); super.clear() }
        })
        store.record(event())
        val snapshot = requireNotNull(f.finish { store.snapshot() })
        val exported = requireNotNull(f.finish { store.cacheSnapshot(snapshot) })
        assertTrue(exported.isFile)
        repeat(20) { store.record(event(2)) }
        f.finish { store.clear() }
        assertFalse(f.log.exists()); assertFalse(f.cache.exists())
        assertNull(store.status.value.lastSavedEpochMillis)
        assertEquals(0L, store.status.value.bytes)
        assertNull(f.finish { store.cacheSnapshot(snapshot) })
        f.finish { store.awaitIdle() }
        assertFalse(f.log.exists())
        store.record(event(3))
        f.finish { store.awaitIdle() }
        assertTrue(f.log.readText().contains("\"quantity\":3"))
        assertFalse(f.log.readText().contains("\"quantity\":2"))
        assertFalse(f.log.readText().contains("\"quantity\":4"))
        store.close()
    }

    @Test fun retentionAndByteCapApplyToDiskAndUtf8Snapshot() {
        val f = fixture(true)
        val store = f.create()
        store.record(event(1))
        f.finish { store.awaitIdle() }
        f.now += DiagnosticStore.DEFAULT_RETENTION_MILLIS + 1
        repeat(30) { store.record(event(2)) }
        val snapshot = requireNotNull(f.finish { store.snapshot() })
        assertFalse(f.log.readText().contains("\"quantity\":1"))
        assertTrue(f.log.length() <= f.byteLimit)
        assertTrue(snapshot.text.toByteArray(Charsets.UTF_8).size <= f.byteLimit)
        assertTrue(snapshot.text.contains("Latest runs")); assertTrue(snapshot.text.contains("Schema: 1"))
        store.close()
    }

    @Test fun writeFailureDoesNotEscapeOrClaimSuccessfulPersistence() {
        val f = fixture(true)
        val disk = object : DiagnosticDisk(f.directory, f.cache) {
            override fun append(text: String) { throw IOException("PRIVATE_EXCEPTION") }
            override fun replace(text: String) { throw IOException("PRIVATE_EXCEPTION") }
        }
        val store = f.create(disk)
        store.safeRecord(event())
        f.finish { store.awaitIdle() }
        assertEquals(DiagnosticStorageError.WRITE, store.status.value.error)
        assertNull(store.status.value.lastSavedEpochMillis)
        assertEquals(0L, store.status.value.bytes)
        assertFalse(store.status.value.toString().contains("PRIVATE_EXCEPTION"))
        store.close()
        DiagnosticSink { throw AssertionError("must not escape") }.safeRecord(event())
        DiagnosticSink { throw AssertionError("must not escape") }.safe().record(event())
    }

    @Test fun readFailureRefusesSnapshotAndSettingsFailureKeepsCaptureOff() {
        val f = fixture()
        val disk = object : DiagnosticDisk(f.directory, f.cache) { override fun read(): String = throw IOException("PRIVATE_ERROR") }
        val store = f.create(disk)
        assertNull(f.finish { store.snapshot() })
        assertEquals(DiagnosticStorageError.SNAPSHOT, store.status.value.error)
        f.preferences.fail = true
        f.finish { store.setEnabled(true) }
        assertFalse(store.status.value.enabled)
        assertEquals(DiagnosticStorageError.SETTINGS_WRITE, store.status.value.error)
        store.close()
    }

    @Test fun runIsCapturedAtEnqueueAndUnknownCodesAndExtraDiskFieldsCannotLeak() {
        val f = fixture(true)
        val store = f.create()
        val firstRun = DiagnosticRun.newId()
        try {
            DiagnosticRun.threadLocal.set(firstRun)
            store.record(event().copy(code = DiagnosticCode.fromSiteCode("PRIVATE_SECRET")))
            DiagnosticRun.threadLocal.set(DiagnosticRun.newId())
            f.finish { store.awaitIdle() }
            assertTrue(f.log.readText().contains(firstRun))
            assertFalse(f.log.readText().contains("PRIVATE_SECRET"))
            store.close(); f.dispatcher.runAll()
            f.log.writeText(f.log.readText().replaceFirst("{", "{\"password\":\"PRIVATE_PASSWORD\",\"url\":\"https://PRIVATE_URL/\","))
            val reopened = f.create()
            val snapshot = requireNotNull(f.finish { reopened.snapshot() })
            assertFalse(snapshot.text.contains("PRIVATE_PASSWORD")); assertFalse(snapshot.text.contains("PRIVATE_URL"))
            assertFalse(f.log.readText().contains("password")); assertTrue(snapshot.text.contains("OTHER"))
            reopened.close()
        } finally { DiagnosticRun.threadLocal.remove() }
    }

    @Test fun oversizedCorruptInputIsReadBoundedAndPrunedAndStaleShareRemovedOnStartup() {
        val f = fixture(true)
        f.directory.mkdirs(); f.log.writeText("PRIVATE_SECRET".repeat(200_000))
        f.cache.mkdirs(); File(f.cache, "old.txt").writeText("old")
        val store = f.create()
        f.finish { store.awaitIdle() }
        assertTrue(f.log.length() <= f.byteLimit)
        assertFalse(f.cache.exists())
        assertFalse(requireNotNull(f.finish { store.snapshot() }).text.contains("PRIVATE_SECRET"))
        store.close()
    }

    @Test fun clearFailureIsTruthfulAndDoesNotDisableExistingCapture() {
        val f = fixture(true)
        val disk = object : DiagnosticDisk(f.directory, f.cache) { override fun clear() { throw IOException("PRIVATE") } }
        val store = f.create(disk)
        store.record(event())
        f.finish { store.awaitIdle() }
        f.finish { store.clear() }
        assertEquals(DiagnosticStorageError.DELETE, store.status.value.error)
        assertTrue(store.status.value.enabled)
        assertTrue(store.status.value.bytes > 0)
        assertFalse(store.status.value.busy)
        store.close()
    }
}
