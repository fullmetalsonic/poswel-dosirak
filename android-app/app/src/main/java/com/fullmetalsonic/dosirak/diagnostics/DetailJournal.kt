package com.fullmetalsonic.dosirak.diagnostics

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class SavedDetail(val detailSchemaVersion: Int = 1, val processInstanceId: String,
    val runId: String, val requestId: String?, val requestOrdinal: Int?, val parentRequestId: String?,
    val captureSegmentId: String, val sequence: Long, val epochMillis: Long, val elapsedMillis: Long,
    val observation: SafeDetailObservation, val structureFingerprint: String? = null)

/** Runs are the rotation unit. No response object, site string or arbitrary path crosses this boundary. */
internal class DetailJournal(private val directory: File, private val gson: Gson, private val wallMillis: () -> Long,
    private val limit: Int = 8 * 1024 * 1024, private val runLimit: Int = 2 * 1024 * 1024,
    private val runCount: Int = DEFAULT_RUN_COUNT, private val snapshotCount: Int = 1024) {
    private val runs = linkedMapOf<String, MutableList<SavedDetail>>()
    private val truncated = linkedMapOf<String, Long>()
    var dropped = 0L; private set
    var queueDropped = 0L; private set
    var corrupt = 0L; private set
    var rotated = 0L; private set
    val bytes: Long get() = directory.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0
    val hasDetails: Boolean get() = runs.values.any { it.isNotEmpty() }

    fun refresh() {
        runs.clear(); truncated.clear()
        dropped = 0; queueDropped = 0; corrupt = 0; rotated = 0
        if (!directory.exists()) return
        val now = wallMillis()
        val candidates = directory.listFiles().orEmpty().filter { it.name.endsWith(".jsonl") && DiagnosticRun.valid(it.name.removeSuffix(".jsonl")) }
            .sortedBy { it.lastModified() }
        for (file in candidates) {
            val id = file.name.removeSuffix(".jsonl")
            val records = mutableListOf<SavedDetail>()
            if (file.length() > runLimit) { corrupt++; check(file.delete()); continue }
            file.bufferedReader().useLines { lines -> lines.forEach { line ->
                if (line.length > MAX_RECORD_BYTES) { corrupt++; return@forEach }
                val record = runCatching { gson.fromJson(line, SavedDetail::class.java) }.getOrNull()
                if (record != null && valid(record) && record.runId == id && record.epochMillis in (now - DiagnosticStore.DEFAULT_RETENTION_MILLIS)..now) records += canonical(record)
                else corrupt++
            } }
            if (records.isEmpty()) check(file.delete())
            else { runs[id] = records; replace(file, records.joinToString("") { gson.toJson(it) + "\n" }) }
        }
        // Only this app's fixed files are read; unrecognized files cannot become export entries.
        directory.listFiles().orEmpty().filter { it.name.endsWith(".pending") }.forEach { check(it.delete()) }
        val state = File(directory, "coverage.json")
        if (state.exists() && state.length() <= 16 * 1024) runCatching {
            val value = gson.fromJson(state.readText(), JsonObject::class.java)
            dropped = value.get("dropped")?.asLong?.coerceAtLeast(0) ?: 0
            queueDropped = value.get("queueDropped")?.asLong?.coerceAtLeast(0) ?: 0
            rotated = value.get("rotated")?.asLong?.coerceAtLeast(0) ?: 0
            corrupt += value.get("corrupt")?.asLong?.coerceAtLeast(0) ?: 0
            value.getAsJsonObject("truncated")?.entrySet()?.take(runCount)?.forEach { (id, count) ->
                if (DiagnosticRun.valid(id) && id in runs) truncated[id] = count.asLong.coerceAtLeast(0)
            }
        }.onFailure { corrupt++ }
        prune(null)
        saveCoverage()
    }

    fun append(input: SavedDetail): Boolean {
        if (!valid(input)) { dropped++; return false }
        val record = canonical(input)
        val line = gson.toJson(record) + "\n"
        val size = line.toByteArray(Charsets.UTF_8).size
        val id = record.runId
        val file = File(directory, "$id.jsonl")
        val reserved = if (record.observation.kind in setOf(DetailKind.RUN_FINISHED, DetailKind.SEGMENT_FINISHED)) 0 else SUMMARY_RESERVE
        if (size > MAX_RECORD_BYTES || file.length() + size > runLimit - reserved ||
            (record.observation.hasSnapshot() && runs[id].orEmpty().count { it.observation.hasSnapshot() } >= snapshotCount)) {
            drop(id); return false
        }
        check(directory.isDirectory || directory.mkdirs())
        val existing = runs[id]
        if (existing == null) runs[id] = mutableListOf()
        // Remove whole older runs before committing a new record.
        prune(id, size)
        if (bytes + size > limit - SUMMARY_RESERVE || runs.values.sumOf { list -> list.count { it.observation.hasSnapshot() } } +
            (if (record.observation.hasSnapshot()) 1 else 0) > snapshotCount) { drop(id); return false }
        FileOutputStream(file, true).use { out -> out.write(line.toByteArray(Charsets.UTF_8)); out.flush(); out.fd.sync() }
        runs.getValue(id).add(record)
        saveCoverage()
        return true
    }

    private fun drop(id: String) { dropped++; truncated[id] = (truncated[id] ?: 0) + 1; saveCoverage() }
    fun noteDropped(count: Long) { if (count > 0) { dropped += count; saveCoverage() } }
    fun noteQueueDropped(total: Long) { if (total != queueDropped) { queueDropped = total.coerceAtLeast(0); saveCoverage() } }
    fun finishSegments(processId: String, now: Long, elapsed: Long, sequence: () -> Long) {
        val lastRecords = runs.values.mapNotNull { records -> records.lastOrNull()?.takeIf {
            it.processInstanceId == processId && it.observation.kind != DetailKind.SEGMENT_FINISHED } }
        lastRecords.forEach { last -> append(last.copy(requestId = null, requestOrdinal = null, parentRequestId = null,
            sequence = sequence(), epochMillis = now, elapsedMillis = elapsed,
            observation = SafeDetailObservation(DetailKind.SEGMENT_FINISHED))) }
    }
    private fun prune(active: String?, incoming: Int = 0) {
        val now = wallMillis()
        runs.keys.toList().forEach { id ->
            if (id != active && runs[id].orEmpty().lastOrNull()?.epochMillis?.let { it in (now - DiagnosticStore.DEFAULT_RETENTION_MILLIS)..now } != true) remove(id)
        }
        while (runs.size > runCount || bytes + incoming > limit - SUMMARY_RESERVE || runs.values.sumOf { list -> list.count { it.observation.hasSnapshot() } } > snapshotCount) {
            val oldest = runs.keys.firstOrNull { it != active } ?: break
            remove(oldest)
        }
    }
    private fun remove(id: String) {
        val file = File(directory, "$id.jsonl")
        if (file.exists()) check(file.delete())
        runs.remove(id); truncated.remove(id); rotated++
    }
    fun clear() { if (directory.exists()) check(directory.deleteRecursively()); runs.clear(); truncated.clear(); dropped = 0; queueDropped = 0; corrupt = 0; rotated = 0 }

    fun archive(target: File, appVersion: String, basic: String, summary: String, basicDropped: Long, detailDropped: Long,
        stillValid: () -> Boolean): File {
        check(stillValid())
        target.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val pending = File(target.parentFile, target.name + ".pending")
        val checksums = linkedMapOf<String, String>()
        var uncompressed = 0L
        var excludedFiles = 0
        try {
            ZipOutputStream(pending.outputStream().buffered()).use { zip ->
                fun entry(name: String, value: String, essential: Boolean = false): Boolean {
                    check(stillValid())
                    val data = value.toByteArray(Charsets.UTF_8)
                    if (!essential && uncompressed + data.size > ZIP_LIMIT - 256 * 1024) { excludedFiles++; return false }
                    check(uncompressed + data.size <= ZIP_LIMIT)
                    zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
                    uncompressed += data.size
                    checksums[name] = sha256(data)
                    check(pending.length() <= ZIP_LIMIT)
                    return true
                }
                entry("summary.txt", summary + detailSummary())
                entry("basic.jsonl", basic)
                // Stream record lines to avoid a second complete archive-sized allocation.
                zip.putNextEntry(ZipEntry("details.jsonl"))
                val detailDigest = MessageDigest.getInstance("SHA-256")
                val included = mutableListOf<Pair<SavedDetail, String?>>()
                var reservedSnapshots = 0L
                for (record in runs.values.flatten()) {
                    check(stillValid())
                    val ref = if (record.observation.hasSnapshot()) "snapshots/${record.observation.route ?: DiagnosticRoute.OTHER}/${included.size + 1}.json" else null
                    val value = gson.toJsonTree(record).asJsonObject
                    value.getAsJsonObject("observation").apply { remove("form"); remove("json"); remove("history") }
                    if (ref != null) value.addProperty("snapshotRef", ref)
                    val data = (gson.toJson(value) + "\n").toByteArray(Charsets.UTF_8)
                    val snapshotBytes = if (ref != null) snapshot(record).toByteArray(Charsets.UTF_8).size else 0
                    if (uncompressed + reservedSnapshots + data.size + snapshotBytes > ZIP_LIMIT - 512 * 1024) { excludedFiles++; continue }
                    zip.write(data); detailDigest.update(data); uncompressed += data.size; included += record to ref
                    reservedSnapshots += snapshotBytes
                }
                zip.closeEntry(); checksums["details.jsonl"] = hex(detailDigest.digest())
                included.forEach { (record, ref) -> if (ref != null) entry(ref, snapshot(record)) }
                val coverage = coverage(basicDropped, detailDropped)
                entry("coverage.json", gson.toJson(coverage), true)
                val records = runs.values.flatten()
                val manifest = mapOf("formatVersion" to 1, "detailSchemaVersion" to 1, "basicSchemaVersion" to 1,
                    "appVersion" to appVersion, "fromEpochMillis" to records.minOfOrNull { it.epochMillis },
                    "structureFingerprint" to "SHA-256 of canonical typed form/json/history only; no response hash",
                    "toEpochMillis" to records.maxOfOrNull { it.epochMillis }, "runs" to runs.size, "records" to records.size,
                    "uncompressedDataBytes" to uncompressed, "excludedFiles" to excludedFiles,
                    "complete" to (excludedFiles == 0 && detailDropped == 0L && dropped == 0L && corrupt == 0L && rotated == 0L && truncated.isEmpty() &&
                        runs.values.all { run -> run.any { it.observation.kind == DetailKind.RUN_FINISHED } &&
                            run.groupBy { it.requestId }.values.all { effectiveCoverage(it) == DetailCoverage.COMPLETE && effectiveOmissions(it).isEmpty() } }),
                    "files" to checksums.keys.toList())
                entry("manifest.json", gson.toJson(manifest), true)
                entry("checksums.sha256", checksums.entries.joinToString("\n", postfix = "\n") { "${it.value}  ${it.key}" }, true)
            }
            check(stillValid() && pending.length() <= ZIP_LIMIT)
            move(pending, target)
            if (!stillValid()) { target.delete(); error("stale") }
            return target
        } finally { if (pending.exists()) pending.delete() }
    }

    private fun coverage(basicDropped: Long = 0, queueDropped: Long = 0): Map<String, Any> = mapOf(
        "basicDropped" to basicDropped, "detailQueueDropped" to queueDropped, "detailStorageDropped" to dropped,
        "corruptRecords" to corrupt, "rotatedRuns" to rotated, "truncatedRuns" to truncated.toMap(),
        "runs" to runs.map { (id, records) -> mapOf("runId" to id,
            "unclosed" to records.none { it.observation.kind == DetailKind.RUN_FINISHED },
            "segments" to records.map { it.captureSegmentId }.distinct(),
            "routes" to DiagnosticRoute.entries.filter { it != DiagnosticRoute.OTHER }.map { route ->
                val observed = records.filter { it.observation.route == route }
                val requests = observed.filter { it.requestId != null }.groupBy { it.requestId }.map { (request, items) ->
                    mapOf("requestId" to request, "requestOrdinal" to items.first().requestOrdinal,
                        "parentRequestId" to items.first().parentRequestId,
                        "coverage" to effectiveCoverage(items), "omissions" to effectiveOmissions(items))
                }
                mapOf("route" to route, "coverage" to when {
                    observed.isEmpty() -> DetailCoverage.NOT_REACHED
                    observed.filter { it.observation.kind == DetailKind.STRUCTURE }.any { it.observation.coverage != DetailCoverage.COMPLETE } -> DetailCoverage.PARTIAL
                    requests.any { it["coverage"] != DetailCoverage.COMPLETE } -> DetailCoverage.PARTIAL
                    else -> effectiveCoverage(observed)
                }, "requests" to requests,
                    "omissions" to observed.flatMap { it.observation.omissions }.distinct().filter {
                        it != DetailOmission.STRUCTURE_NOT_EXTRACTED || requests.any { request ->
                            (request["omissions"] as? List<*>)?.contains(DetailOmission.STRUCTURE_NOT_EXTRACTED) == true } })
            }) })
    private fun effectiveCoverage(items: List<SavedDetail>): DetailCoverage {
        val structures = items.filter { it.observation.kind == DetailKind.STRUCTURE }
        if (structures.isNotEmpty() && structures.none { hasExtractedStructure(it.observation) } &&
            structures.any { it.observation.coverage == DetailCoverage.REACHED_NOT_EXTRACTED }) return DetailCoverage.REACHED_NOT_EXTRACTED
        if (structures.isNotEmpty()) return if (structures.all { it.observation.coverage == DetailCoverage.COMPLETE }) DetailCoverage.COMPLETE else DetailCoverage.PARTIAL
        val http = items.filter { it.observation.kind == DetailKind.HTTP }
        if (http.isNotEmpty()) return if (http.any { it.observation.coverage != DetailCoverage.COMPLETE }) DetailCoverage.HTTP_ONLY else DetailCoverage.COMPLETE
        return if (items.any { it.observation.coverage != DetailCoverage.COMPLETE }) DetailCoverage.PARTIAL else DetailCoverage.COMPLETE
    }
    private fun effectiveOmissions(items: List<SavedDetail>): List<DetailOmission> {
        val extracted = items.any { hasExtractedStructure(it.observation) }
        return items.flatMap { it.observation.omissions }.distinct().filter { !extracted || it != DetailOmission.STRUCTURE_NOT_EXTRACTED }
    }
    private fun hasExtractedStructure(value: SafeDetailObservation): Boolean = value.kind == DetailKind.STRUCTURE &&
        value.coverage in setOf(DetailCoverage.COMPLETE, DetailCoverage.PARTIAL) && value.hasSnapshot()
    private fun detailSummary(): String = buildString {
        append("\nDetail facts (UTC wall time; elapsed time only within one process):\n")
        runs.forEach { (id, records) ->
            val last = records.lastOrNull()
            append("Run ").append(id).append(" processes=").append(records.map { it.processInstanceId }.distinct().size)
                .append(" segments=").append(records.map { it.captureSegmentId }.distinct().size)
                .append(" lastObserved=").append(last?.observation?.stage ?: last?.observation?.kind)
                .append(" result=").append(last?.observation?.result ?: DiagnosticResult.UNKNOWN)
                .append(" unclosed=").append(records.none { it.observation.kind == DetailKind.RUN_FINISHED })
                .append(" partialStart=").append(records.any { DetailOmission.PARTIAL_START in it.observation.omissions })
                .append(" truncated=").append(truncated[id] ?: 0).append('\n')
        }
        append("Missing evidence: basicDropped/detailsQueueDropped/storageDropped/corrupt/rotated and per-request omissions are in coverage.json.\n")
        append("Candidates: no cause is inferred automatically from missing records.\n")
        append("Unknown: server state, unseen user notifications and unobserved requests; an unclosed run is not proof of failure or non-submission.\n")
    }
    private fun saveCoverage() {
        if (!directory.exists() && !hasDetails && dropped == 0L && queueDropped == 0L && corrupt == 0L) return
        check(directory.isDirectory || directory.mkdirs())
        replace(File(directory, "coverage.json"), gson.toJson(mapOf("dropped" to dropped, "queueDropped" to queueDropped, "corrupt" to corrupt,
            "rotated" to rotated, "truncated" to truncated.toMap())))
    }
    private fun valid(record: SavedDetail): Boolean = runCatching {
        record.detailSchemaVersion == 1 && DiagnosticRun.valid(record.processInstanceId) && DiagnosticRun.valid(record.runId) &&
            DiagnosticRun.valid(record.captureSegmentId) && (record.requestId == null || DiagnosticRun.valid(record.requestId)) &&
            (record.parentRequestId == null || DiagnosticRun.valid(record.parentRequestId)) && record.sequence >= 0 && record.elapsedMillis >= 0 &&
            safeDetailCopy(record.observation) != null
    }.getOrDefault(false)
    private fun canonical(record: SavedDetail): SavedDetail {
        val safe = requireNotNull(safeDetailCopy(record.observation))
        val fingerprint = if (safe.hasSnapshot()) sha256(gson.toJson(mapOf("form" to safe.form, "json" to safe.json, "history" to safe.history)).toByteArray(Charsets.UTF_8)) else null
        return record.copy(observation = safe, structureFingerprint = fingerprint)
    }
    private fun snapshot(record: SavedDetail): String = gson.toJson(mapOf("form" to record.observation.form,
        "json" to record.observation.json, "history" to record.observation.history, "structureFingerprint" to record.structureFingerprint))
    companion object {
        // Small order/history runs exhausted 32 entries long before the unchanged byte budget.
        const val DEFAULT_RUN_COUNT = 256
        const val MAX_RECORD_BYTES = 32 * 1024
        const val SUMMARY_RESERVE = 16 * 1024
        const val ZIP_LIMIT = 12L * 1024 * 1024
        private fun replace(file: File, text: String) {
            val pending = File(file.parentFile, file.name + ".pending")
            try { FileOutputStream(pending).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }; move(pending, file) }
            finally { if (pending.exists()) pending.delete() }
        }
        private fun move(source: File, target: File) {
            try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
        private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}

private fun SafeDetailObservation.hasSnapshot(): Boolean = form != null || json != null || history != null

/** Validate and detach caller-owned collections before they can enter the bounded queue. */
internal fun safeDetailCopy(value: SafeDetailObservation): SafeDetailObservation? = runCatching {
    require(value.kind.name.isNotEmpty() && value.coverage.name.isNotEmpty())
    require(value.parentRunId == null || DiagnosticRun.valid(value.parentRunId))
    require(value.httpStatus == null || value.httpStatus in 100..599)
    require(value.responseBytes == null || value.responseBytes in 0..2_000_000)
    require(value.durationMillis == null || value.durationMillis in 0..86_400_000)
    require(value.requestSafeNumbers())
    val form = value.form?.let { form ->
        require(form.personalValuesExcluded && form.fields.size <= 64 && form.omittedUnknownCount in 0..1_000_000)
        form.copy(fields = form.fields.map { field ->
            require(field.type.name.isNotEmpty() && field.valueExcluded && (field.name != null ||
                (field.unknownFieldSlot != null && field.unknownFieldSlot in 1..500)))
            require(field.optionCount in 0..1_000_000 && field.quantityOptions.size <= 32 && field.quantityOptions.all { it in 0..10000 })
            require(field.minimum == null || field.minimum in 0..10000)
            require(field.maximum == null || field.maximum in 0..10000)
            field.copy(quantityOptions = field.quantityOptions.toList())
        })
    }
    var nodes = 0
    fun json(node: SafeJsonNode, depth: Int): SafeJsonNode {
        require(++nodes <= 500 && depth <= 20 && node.type.name.isNotEmpty())
        require(node.unknownKeySlot == null || node.unknownKeySlot in 1..500)
        require(node.count == null || node.count in 0..2_000_000)
        require(node.children.size <= 64)
        return node.copy(children = node.children.map { json(it, depth + 1) })
    }
    val structure = value.json?.let { require(it.omittedUnknownCount in 0..1_000_000); it.copy(root = json(it.root, 0)) }
    val history = value.history?.let { history ->
        require(value.route == DiagnosticRoute.HISTORY && history.rows.size <= 32 && history.rowsExamined == history.rows.size)
        require(history.rowsExamined in 0..32 && (!history.bodyPresent || history.tablePresent))
        require(!history.allRowsScanned || (history.bodyPresent && !history.partial))
        history.copy(rows = history.rows.map { row ->
            require(row.date == null || row.date.year in 2000..2100)
            require(row.quantity == null || row.quantity in 0..10000)
            require(row.total == null || row.total in 0..1_000_000_000)
            require(row.status.name.isNotEmpty())
            require(row.cancelHandler == null || (row.cancelLinkPresent && row.cancelHandler.name.isNotEmpty()))
            row.copy()
        })
    }
    require(4096L + (form?.fields?.size ?: 0) * 640L + nodes * 384L +
        (if (history == null) 0 else 512L + history.rows.size * 640L) <= DetailJournal.MAX_RECORD_BYTES)
    val business = value.business?.let {
        require(it.targetDate == null || it.targetDate.year in 2000..2100)
        require(listOfNotNull(it.quantity, it.minimumQuantity, it.maximumQuantity).all { number -> number in 0..10000 })
        require(listOfNotNull(it.unitPrice, it.total).all { number -> number in 0..1_000_000_000 })
        require(it.quantityOptions.size <= 32 && it.quantityOptions.all { number -> number in 0..10000 })
        require(it.resultCount == null || it.resultCount in 0..10000)
        it.copy(quantityOptions = it.quantityOptions.toList())
    }
    value.environment?.let { require(it.apiLevel == null || it.apiLevel in 1..1000); require(it.timeZone.name.isNotEmpty() && it.network.name.isNotEmpty()) }
    value.copy(form = form, json = structure, history = history, business = business, omissions = value.omissions.toSet())
}.getOrNull()

private fun SafeDetailObservation.requestSafeNumbers(): Boolean =
    (redirectCount == null || redirectCount in 0..100) && (generation == null || generation >= 0) &&
        (accountGeneration == null || accountGeneration >= 0) && (extractionNanos == null || extractionNanos >= 0) &&
        (visitedNodes == null || visitedNodes in 0..500)
