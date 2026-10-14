package com.fullmetalsonic.dosirak.diagnostics

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.time.Instant
import java.time.LocalDate

internal interface DiagnosticPreference {
    fun readEnabled(): Boolean
    fun writeEnabled(enabled: Boolean): Boolean
    fun readLastSaved(): Long? = null
    fun writeLastSaved(epochMillis: Long?): Boolean = true
}

internal open class DiagnosticDisk(private val directory: File, private val shareDirectory: File) {
    private val log get() = File(directory, "events.jsonl")
    open fun bytes(): Long = if (log.exists()) log.length() else 0
    open fun read(): String {
        if (!log.exists()) return ""
        return RandomAccessFile(log, "r").use { input ->
            val start = (input.length() - DiagnosticStore.DEFAULT_BYTE_LIMIT).coerceAtLeast(0)
            input.seek(start)
            val bytes = ByteArray((input.length() - start).toInt())
            input.readFully(bytes)
            val text = bytes.toString(Charsets.UTF_8)
            if (start == 0L) text else text.substringAfter('\n', "")
        }
    }
    open fun replace(text: String) {
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, "events.pending")
        try {
            writeFile(temporary, text, false)
            try { Files.move(temporary.toPath(), log.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), log.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { if (temporary.exists()) temporary.delete() }
    }
    open fun append(text: String) {
        check(directory.isDirectory || directory.mkdirs())
        writeFile(log, text, true)
    }
    open fun clear() {
        if (directory.exists()) check(directory.deleteRecursively())
        clearCache()
    }
    open fun clearCache() { if (shareDirectory.exists()) check(shareDirectory.deleteRecursively()) }
    open fun clearPending() { File(directory, "events.pending").let { if (it.exists()) check(it.delete()) } }
    open fun cache(text: String): File {
        clearCache()
        check(shareDirectory.isDirectory || shareDirectory.mkdirs())
        val target = File(shareDirectory, "poswel-analysis.txt")
        writeFile(target, text, false)
        return target
    }
    private fun writeFile(file: File, text: String, append: Boolean) {
        FileOutputStream(file, append).use { output -> output.write(text.toByteArray(Charsets.UTF_8)); output.flush(); output.fd.sync() }
    }
}

internal class DiagnosticStore(
    private val disk: DiagnosticDisk,
    private val preference: DiagnosticPreference,
    private val appVersion: String,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val byteLimit: Int = DEFAULT_BYTE_LIMIT,
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS
) : DiagnosticController {
    private data class SavedEvent(val schema: Int, val appVersion: String, val runId: String, val utc: String,
        val epochMillis: Long, val elapsedMillis: Long, val event: DiagnosticEvent)
    private data class QueuedEvent(val epoch: Long, val event: SavedEvent)
    private data class SavedLine(val event: SavedEvent, val line: String, val bytes: Int)
    private sealed interface Command {
        data class Enabled(val enabled: Boolean, val done: CompletableDeferred<Unit>) : Command
        data class Clear(val done: CompletableDeferred<Unit>) : Command
        data class Snapshot(val done: CompletableDeferred<DiagnosticSnapshot?>) : Command
        data class Cache(val snapshot: DiagnosticSnapshot, val done: CompletableDeferred<File?>) : Command
        data class Barrier(val done: CompletableDeferred<Unit>) : Command
    }
    private val lock = Any()
    private val controls = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val events = Channel<QueuedEvent>(128)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var eraseEpoch = 0L
    private var paused = false
    private var enabled = false
    private var saved = emptyList<SavedLine>()
    private val mutableStatus = MutableStateFlow(DiagnosticStatus())
    override val status: StateFlow<DiagnosticStatus> = mutableStatus.asStateFlow()
    private val gson = GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java, JsonSerializer<LocalDate> { value, _, _ -> JsonPrimitive(value.toString()) })
        .registerTypeAdapter(LocalDate::class.java, JsonDeserializer<LocalDate> { value, _, _ -> LocalDate.parse(value.asString) })
        .create()

    init {
        require(validVersion(appVersion) && byteLimit in 2048..DEFAULT_BYTE_LIMIT && retentionMillis > 0)
        try { enabled = preference.readEnabled(); update { it.copy(lastSavedEpochMillis = preference.readLastSaved()) } }
        catch (_: Exception) { update { it.copy(error = DiagnosticStorageError.SETTINGS_READ) } }
        update { it.copy(enabled = enabled) }
        scope.launch {
            try { disk.clearCache() } catch (_: Exception) { update { it.copy(error = DiagnosticStorageError.DELETE) } }
            try { disk.clearPending() } catch (_: Exception) { update { it.copy(error = DiagnosticStorageError.DELETE) } }
            refresh()
            while (true) {
                val ready = commands.tryReceive().getOrNull()
                if (ready != null) handle(ready)
                else select<Unit> {
                    commands.onReceive { handle(it) }
                    events.onReceive { write(it) }
                }
            }
        }
    }

    override fun record(event: DiagnosticEvent) {
        try {
            synchronized(lock) {
                if (!enabled || paused || !validEvent(event)) return
                val now = wallMillis()
                val record = SavedEvent(1, appVersion, DiagnosticRun.currentId() ?: DiagnosticRun.newId(),
                    Instant.ofEpochMilli(now).toString(), now, elapsedMillis(), event)
                if (events.trySend(QueuedEvent(eraseEpoch, record)).isFailure) {
                    update { it.copy(dropped = it.dropped + 1, error = DiagnosticStorageError.QUEUE_FULL) }
                }
            }
        } catch (_: Exception) { update { it.copy(dropped = it.dropped + 1, error = DiagnosticStorageError.WRITE) } }
    }

    override suspend fun setEnabled(enabled: Boolean) = controls.withLock {
        update { it.copy(busy = true) }
        if (!enabled) synchronized(lock) {
            this.enabled = false
            eraseEpoch++
            dropPending()
            update { it.copy(enabled = false) }
        }
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Enabled(enabled, done))
        done.await()
    }

    override suspend fun clear() = controls.withLock {
        synchronized(lock) {
            paused = true
            eraseEpoch++
            dropPending()
            update { it.copy(busy = true) }
        }
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Clear(done))
        done.await()
    }

    override suspend fun snapshot(): DiagnosticSnapshot? = controls.withLock {
        val done = CompletableDeferred<DiagnosticSnapshot?>()
        commands.send(Command.Snapshot(done))
        done.await()
    }

    override suspend fun cacheSnapshot(snapshot: DiagnosticSnapshot): File? = controls.withLock {
        val done = CompletableDeferred<File?>()
        commands.send(Command.Cache(snapshot, done))
        done.await()
    }

    internal suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        commands.send(Command.Barrier(done))
        done.await()
    }
    internal fun close() { scope.cancel(); events.close(); commands.close() }

    private fun handle(command: Command) {
        when (command) {
            is Command.Enabled -> {
                try {
                    check(preference.writeEnabled(command.enabled))
                    synchronized(lock) { enabled = command.enabled; update { it.copy(enabled = enabled, error = null) } }
                } catch (_: Exception) { update { it.copy(error = DiagnosticStorageError.SETTINGS_WRITE) } }
                finally { update { it.copy(busy = false) } }
                command.done.complete(Unit)
            }
            is Command.Clear -> {
                try {
                    disk.clear()
                    saved = emptyList()
                    update { it.copy(lastSavedEpochMillis = null, bytes = 0, error = null) }
                    try {
                        if (!preference.writeLastSaved(null)) update { it.copy(error = DiagnosticStorageError.SETTINGS_WRITE) }
                    } catch (_: Exception) { update { it.copy(error = DiagnosticStorageError.SETTINGS_WRITE) } }
                } catch (_: Exception) { refresh(false); update { it.copy(error = DiagnosticStorageError.DELETE) } }
                finally {
                    synchronized(lock) { paused = false; update { it.copy(busy = false) } }
                    command.done.complete(Unit)
                }
            }
            is Command.Snapshot -> {
                update { it.copy(busy = true) }
                try {
                    drainEvents()
                    check(refresh())
                    val records = saved.map { it.event }
                    val runs = records.groupBy { it.runId }.entries.toList().takeLast((byteLimit / 1024).coerceAtMost(20)).joinToString("\n") { (id, events) ->
                        val last = events.last()
                        val notification = events.lastOrNull { it.event.notificationEvent != null }
                        "Run $id date=${events.lastOrNull { it.event.date != null }?.event?.date ?: "none"} " +
                            "UTC=${events.first().utc}..${last.utc} final=${last.event.stage ?: "none"}/${last.event.result ?: "none"}/${last.event.code ?: "none"} " +
                            "notification=${notification?.event?.notificationEvent ?: "none"}/${notification?.event?.result ?: "none"}/${notification?.event?.code ?: "none"}"
                    }
                    val summary = "Poswel analysis log\nSchema: 1\nApp version: $appVersion\n" +
                        "Collected events: ${records.size}\nSaved bytes: ${status.value.bytes}\nDropped events: ${status.value.dropped}\n" +
                        "Last saved UTC: ${status.value.lastSavedEpochMillis?.let { Instant.ofEpochMilli(it) } ?: "none"}\n\n" +
                        "Latest runs (up to 20; receiver/service runs may be separate):\n$runs\n\nJSONL\n"
                    val exportBudget = byteLimit - summary.toByteArray(Charsets.UTF_8).size
                    check(exportBudget >= 0)
                    var exported = 0
                    val jsonl = saved.asReversed().takeWhile { exported += it.bytes; exported <= exportBudget }.asReversed().joinToString("") { it.line }
                    val text = summary + jsonl
                    check(text.toByteArray(Charsets.UTF_8).size <= byteLimit)
                    command.done.complete(DiagnosticSnapshot(text, "poswel-analysis.txt", synchronized(lock) { eraseEpoch }))
                } catch (_: Exception) {
                    update { it.copy(error = DiagnosticStorageError.SNAPSHOT) }
                    command.done.complete(null)
                } finally { update { it.copy(busy = false) } }
            }
            is Command.Cache -> {
                update { it.copy(busy = true) }
                try {
                    val valid = synchronized(lock) { !paused && command.snapshot.eraseEpoch == eraseEpoch }
                    command.done.complete(if (valid) disk.cache(command.snapshot.text) else null)
                } catch (_: Exception) {
                    update { it.copy(error = DiagnosticStorageError.SNAPSHOT) }
                    command.done.complete(null)
                } finally { update { it.copy(busy = false) } }
            }
            is Command.Barrier -> { drainEvents(); command.done.complete(Unit) }
        }
    }

    private fun write(queued: QueuedEvent) {
        if (!synchronized(lock) { enabled && !paused && queued.epoch == eraseEpoch }) return
        val previous = saved
        try {
            val now = wallMillis()
            val line = gson.toJson(queued.event) + "\n"
            val fresh = SavedLine(queued.event, line, line.toByteArray(Charsets.UTF_8).size)
            val retained = saved.filter { it.event.epochMillis in (now - retentionMillis)..now } + fresh
            var size = 0
            saved = retained.asReversed().takeWhile { size += it.bytes; size <= byteLimit - 1024 }.asReversed()
            if (saved.size == previous.size + 1 && saved.dropLast(1) == previous) disk.append(line)
            else disk.replace(saved.joinToString("") { it.line })
            val completedAt = wallMillis()
            update { it.copy(bytes = saved.sumOf { line -> line.bytes }.toLong(), lastSavedEpochMillis = completedAt,
                error = if (it.error == DiagnosticStorageError.QUEUE_FULL) it.error else null) }
            try {
                if (!preference.writeLastSaved(completedAt)) update { it.copy(error = DiagnosticStorageError.SETTINGS_WRITE) }
            } catch (_: Exception) { update { it.copy(error = DiagnosticStorageError.SETTINGS_WRITE) } }
        } catch (_: Exception) {
            saved = previous
            refresh(false)
            update { it.copy(dropped = it.dropped + 1, error = DiagnosticStorageError.WRITE) }
        }
    }

    private fun drainEvents() { repeat(128) { write(events.tryReceive().getOrNull() ?: return) } }
    private fun dropPending() {
        var dropped = 0L
        while (events.tryReceive().isSuccess) dropped++
        if (dropped > 0) update { it.copy(dropped = it.dropped + dropped) }
    }
    private fun refresh(prune: Boolean = true): Boolean {
        try {
            val text = disk.read()
            val kept = retained(text)
            val canonical = kept.joinToString("") { gson.toJson(it) + "\n" }
            if (prune && (text != canonical || disk.bytes() > byteLimit)) disk.replace(canonical)
            saved = kept.map { val line = gson.toJson(it) + "\n"; SavedLine(it, line, line.toByteArray(Charsets.UTF_8).size) }
            update { it.copy(bytes = if (prune) canonical.toByteArray(Charsets.UTF_8).size.toLong() else disk.bytes(),
                lastSavedEpochMillis = if (kept.isEmpty()) null else it.lastSavedEpochMillis ?: kept.last().epochMillis) }
            return true
        } catch (_: Exception) {
            update { current -> current.copy(bytes = runCatching { disk.bytes() }.getOrDefault(current.bytes), error = DiagnosticStorageError.READ) }
            return false
        }
    }
    private fun retained(text: String): List<SavedEvent> {
        check(text.toByteArray(Charsets.UTF_8).size <= DEFAULT_BYTE_LIMIT * 2)
        val now = wallMillis()
        return bound(text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching { gson.fromJson(line, SavedEvent::class.java) }.getOrNull()?.takeIf {
                runCatching { it.schema == 1 && validVersion(it.appVersion) && DiagnosticRun.valid(it.runId) &&
                    Instant.parse(it.utc).toEpochMilli() == it.epochMillis && it.epochMillis in (now - retentionMillis)..now &&
                    it.elapsedMillis >= 0 && validEvent(it.event) }.getOrDefault(false)
            }
        }.toList())
    }
    private fun bound(records: List<SavedEvent>): List<SavedEvent> {
        var total = 0
        return records.asReversed().takeWhile { total += gson.toJson(it).toByteArray(Charsets.UTF_8).size + 1; total <= byteLimit - 1024 }.asReversed()
    }
    private fun update(change: (DiagnosticStatus) -> DiagnosticStatus) = synchronized(lock) { mutableStatus.value = change(mutableStatus.value) }

    private fun validEvent(event: DiagnosticEvent): Boolean = runCatching {
        event.event.name.isNotEmpty() && (event.quantity == null || event.quantity in 0..5) &&
            (event.amount == null || event.amount in 0..1_000_000L) && (event.generation == null || event.generation >= 0) &&
            (event.accountGeneration == null || event.accountGeneration >= 0) && (event.httpStatus == null || event.httpStatus in 100..599) &&
            (event.durationMillis == null || event.durationMillis in 0..86_400_000L) &&
            (event.targetEpochMillis == null || event.targetEpochMillis > 0) && (event.date == null || event.date.year in 2000..2100)
    }.getOrDefault(false)

    companion object {
        const val DEFAULT_BYTE_LIMIT = 2 * 1024 * 1024
        const val DEFAULT_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        private fun validVersion(version: String): Boolean = version.matches(Regex("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}(?:-test)?"))
    }
}
