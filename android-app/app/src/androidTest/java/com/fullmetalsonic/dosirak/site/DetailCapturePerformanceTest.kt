package com.fullmetalsonic.dosirak.site

import android.os.Bundle
import android.os.Build
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.diagnostics.*
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Locale
import kotlin.math.ceil

/** Isolated extraction only: no app runtime, network, account, order, filesystem or device settings. */
@RunWith(AndroidJUnit4::class)
class DetailCapturePerformanceTest {
    private val base = "https://dosirak.poswel.co.kr/".toHttpUrl()
    private val gson = GsonBuilder().registerTypeAdapter(LocalDate::class.java,
        JsonSerializer<LocalDate> { value, _, _ -> JsonPrimitive(value.toString()) }).create()

    private data class Fixture(val route: DiagnosticRoute, val document: Document? = null, val json: JsonElement? = null)
    private data class Sample(val responses: List<Long>, val runNanos: Long, val partial: Int,
        val dropped: Int, val missingShape: Int, val outputBytes: List<Long>, val records: Int,
        val cpuNanos: Long?, val heapSamples: List<Long>, val gcCountDelta: Long?, val blockingGcCountDelta: Long?)

    private class SafeSink(private val enabled: Boolean) : DiagnosticSink {
        val details = mutableListOf<SafeDetailObservation>()
        var dropped = 0
        private val processId = DiagnosticRun.newId()
        private val segmentId = DiagnosticRun.newId()
        override fun record(event: DiagnosticEvent) = Unit
        override fun captureDetail(runId: String, requestId: String?, requestOrdinal: Int?, parentRequestId: String?): DetailCapture? =
            if (enabled) DetailCapture(processId, runId, requestId, requestOrdinal, parentRequestId, segmentId, 0) else null
        override fun isDetailCaptureCurrent(capture: DetailCapture): Boolean = enabled
        override fun recordDetail(capture: DetailCapture, detail: SafeDetailObservation) {
            val safe = safeDetailCopy(detail)
            if (safe == null) dropped++ else details.add(safe)
        }
    }

    @Test fun warmedSixResponseRunsMeasureRealOnOffCostAndPreserveRequiredEvidence() {
        // All source objects are parsed before timing, then reused unchanged in both modes.
        val fixtures = listOf(
            Fixture(DiagnosticRoute.HOME, document = Jsoup.parse("""<form action='/login.check.php' method='post'>
                <input name='uid' required><input name='pwd' type='password' required><input name='utk' type='hidden'>
                <button type='submit'>Continue</button></form>""")),
            Fixture(DiagnosticRoute.PROFILE, document = Jsoup.parse("<input name='uid' readonly><input name='pwd' type='password'>")),
            Fixture(DiagnosticRoute.HOME, document = Jsoup.parse("""<form action='/' method='get'>
                <select name='qty[]' min='1' max='5' required><option value='1'>1</option><option value='2'>2</option>
                <option value='3'>3</option><option value='4'>4</option><option value='5'>5</option></select>
                <select id='loc'><option>Selected</option></select><button id='go-pay'>Continue</button></form>""")),
            Fixture(DiagnosticRoute.CHECKOUT, document = Jsoup.parse("""<form action='/togobox/order.reg.php' method='post'>
                <input name='qty[]' type='number' min='1' max='5' required><input name='uid[]' type='hidden'>
                <input name='payroll' type='radio' required><input name='new_required_contract' type='checkbox' required>
                <button id='deliveryCxl' type='submit' formaction='/togobox/order.reg.php' formmethod='post'>Submit</button></form>""")),
            Fixture(DiagnosticRoute.TEMPORARY_ORDER, json = JsonParser.parseString("""{"code":"0000","data":{"success":true}}""")),
            Fixture(DiagnosticRoute.TEMPORARY_CART, json = JsonParser.parseString("""{"code":"0000","data":[{"me_date":"2026-10-16","me_quantity":2,"me_price":10000,"new_contract":{"items":[true,null]}}]}"""))
        )
        val originalDocuments = fixtures.map { it.document?.outerHtml() }
        val originalJson = fixtures.map { it.json?.toString() }
        val extractor = SafeSiteExtractor()
        repeat(12) { sample(extractor, fixtures, false); sample(extractor, fixtures, true) }
        val off = mutableListOf<Sample>()
        val on = mutableListOf<Sample>()
        repeat(40) { iteration ->
            if (iteration % 2 == 0) {
                off.add(sample(extractor, fixtures, false)); on.add(sample(extractor, fixtures, true))
            } else {
                on.add(sample(extractor, fixtures, true)); off.add(sample(extractor, fixtures, false))
            }
        }
        val responseP95 = percentile(on.flatMap { it.responses }, 0.95)
        val runP95 = percentile(on.map { it.runNanos }, 0.95)
        val targetMet = responseP95 <= 10_000_000L && runP95 <= 50_000_000L
        report("DETAIL_CAPTURE_PERFORMANCE", listOf(
            "scope=preparsed_synthetic_fixtures_not_full_live_site_or_end_to_end_order",
            "timed=extractor_and_safe_DTO_validation_copy;excluded=parsing_HTTP_queue_disk_ZIP",
            "resource_queries=outside_response_wall_timing;thread_CPU_delta_scope=six_response_fixture_harness_including_validation_serialization_and_resource_sampling",
            "memory=sampled_heap_high_water_at_run_start_and_after_each_response_not_exact_peak;GC=process_wide_counter_delta_not_exact_extractor_attribution;unsupported_or_failed_queries=UNAVAILABLE",
            "warmup_runs_per_mode=12;measured_runs_per_mode=40;responses_per_run=6;response_samples_per_mode=240",
            summary("OFF", off), summary("ON", on),
            "target_response_p95_ms=10;target_run_p95_ms=50;target_result=${if (targetMet) "PASS" else "FAIL"}",
            "order_path_parity=separate_PoswelDetailTest_on_off_checkout_submit_history_HTTP_count_test"
        ).joinToString("\n"))
        assertEquals(originalDocuments, fixtures.map { it.document?.outerHtml() })
        assertEquals(originalJson, fixtures.map { it.json?.toString() })
        assertEquals("OFF must not produce safe DTOs", 0, off.sumOf { it.records })
        assertTrue("OFF must not serialize any output", off.all { it.outputBytes.all { bytes -> bytes == 0L } })
        assertEquals("Required form/quantity/JSON evidence must survive every ON response", 0, on.sumOf { it.missingShape })
        assertEquals("No accepted ON fixture may be dropped", 0, on.sumOf { it.dropped })
        assertEquals("Normal fixture observations must be complete", 0, on.sumOf { it.partial })
        assertTrue("Each response's safe output must fit 32 KiB", on.flatMap { it.outputBytes }.all { it <= 32 * 1024 })
        assertTrue("Measured ON response p95 exceeded 10ms: ${millis(responseP95)}ms", responseP95 <= 10_000_000L)
        assertTrue("Measured six-response run p95 exceeded 50ms: ${millis(runP95)}ms", runP95 <= 50_000_000L)
    }

    @Test fun hugeDocumentStopsWithBoundedPartialEvidenceAndNoSourceMutation() {
        val doc = Jsoup.parse("<form><input name='qty[]' min='1' max='5' required value='" +
            "S".repeat(262144) + "'>" + "<span></span>".repeat(10000) + "</form>")
        val original = doc.outerHtml()
        val sink = SafeSink(true)
        val capture = sink.captureDetail(DiagnosticRun.newId(), DiagnosticRun.newId(), 1, null)
        val extractor = SafeSiteExtractor()
        val started = System.nanoTime()
        extractor.document(doc, base, sink, capture, DiagnosticRoute.CHECKOUT)
        val elapsed = System.nanoTime() - started
        val bytes = gson.toJson(sink.details).toByteArray(Charsets.UTF_8).size
        report("DETAIL_CAPTURE_BOUNDED_DOM", "scope=synthetic_10000_spans_and_262144_char_attribute;" +
            "elapsed_ms=${millis(elapsed)};records=${sink.details.size};dropped=${sink.dropped};output_bytes=$bytes;" +
            "visited_nodes=${sink.details.maxOfOrNull { it.visitedNodes ?: 0 } ?: 0};" +
            "omissions=${sink.details.flatMap { it.omissions }.toSet().joinToString(",")};" +
            "cooperative_budget_not_a_hard_realtime_guarantee=true")
        assertEquals(original, doc.outerHtml())
        assertEquals(0, sink.dropped)
        assertTrue(sink.details.isNotEmpty())
        assertTrue(sink.details.all { it.coverage == DetailCoverage.PARTIAL })
        assertTrue(sink.details.all { (it.visitedNodes ?: 0) <= 500 })
        assertTrue(sink.details.all { (it.form?.fields?.size ?: 0) <= 64 })
        assertTrue(bytes <= 32 * 1024)
        assertTrue(sink.details.any { detail -> detail.omissions.any {
            it in setOf(DetailOmission.NODE_LIMIT, DetailOmission.TIME_LIMIT, DetailOmission.BYTE_LIMIT, DetailOmission.RUN_TIME_LIMIT)
        } })
    }

    private fun sample(extractor: SafeSiteExtractor, fixtures: List<Fixture>, enabled: Boolean): Sample {
        val sink = SafeSink(enabled)
        val runId = DiagnosticRun.newId()
        val times = mutableListOf<Long>()
        val sizes = mutableListOf<Long>()
        val heaps = mutableListOf<Long>()
        var missing = 0
        val gcBefore = runtimeCounter("art.gc.gc-count")
        val blockingGcBefore = runtimeCounter("art.gc.blocking-gc-count")
        heapUsed()?.let(heaps::add)
        val cpuBefore = threadCpuNanos()
        fixtures.forEachIndexed { index, fixture ->
            val capture = sink.captureDetail(runId, DiagnosticRun.newId(), index + 1, null)
            val before = sink.details.size
            val started = System.nanoTime()
            if (fixture.document != null) extractor.document(fixture.document, base, sink, capture, fixture.route)
            else extractor.json(requireNotNull(fixture.json), sink, capture, fixture.route)
            times.add(System.nanoTime() - started)
            heapUsed()?.let(heaps::add)
            val response = sink.details.subList(before, sink.details.size)
            sizes.add(if (response.isEmpty()) 0 else gson.toJson(response).toByteArray(Charsets.UTF_8).size.toLong())
            if (enabled && !hasRequiredShape(index, response)) missing++
        }
        val cpuAfter = threadCpuNanos()
        val gcAfter = runtimeCounter("art.gc.gc-count")
        val blockingGcAfter = runtimeCounter("art.gc.blocking-gc-count")
        return Sample(times, times.sum(), sink.details.count { it.coverage != DetailCoverage.COMPLETE },
            sink.dropped, missing, sizes, sink.details.size, counterDelta(cpuBefore, cpuAfter), heaps,
            counterDelta(gcBefore, gcAfter), counterDelta(blockingGcBefore, blockingGcAfter))
    }

    private fun hasRequiredShape(index: Int, response: List<SafeDetailObservation>): Boolean {
        val fields = response.flatMap { it.form?.fields.orEmpty() }
        return when (index) {
            0 -> fields.any { it.name == SafeFieldName.UID && it.required } && fields.any { it.name == SafeFieldName.PWD && it.required }
            1 -> fields.any { it.name == SafeFieldName.UID && it.readOnly }
            2 -> fields.any { it.name == SafeFieldName.QTY && it.required && it.quantityOptions == listOf(1, 2, 3, 4, 5) }
            3 -> fields.any { it.name == SafeFieldName.QTY && it.required && it.minimum == 1 && it.maximum == 5 } &&
                fields.any { it.unknownFieldSlot != null && it.required && it.type == SafeFieldType.CHECKBOX } &&
                fields.any { it.actionRoute == DiagnosticRoute.SUBMISSION && it.type == SafeFieldType.SUBMIT }
            4 -> response.any { it.json?.root?.children?.any { node -> node.key == SafeJsonKey.CODE && node.type == SafeJsonType.STRING } == true }
            5 -> response.any { it.json?.root?.children?.any { node -> node.key == SafeJsonKey.DATA && node.type == SafeJsonType.ARRAY && node.children.isNotEmpty() } == true }
            else -> false
        }
    }

    private fun summary(mode: String, samples: List<Sample>): String {
        val responses = samples.flatMap { it.responses }
        val runs = samples.map { it.runNanos }
        val bytes = samples.flatMap { it.outputBytes }
        val heaps = samples.flatMap { it.heapSamples }
        return "mode=$mode;response_ms_p50=${millis(percentile(responses, 0.50))};" +
            "response_ms_p95=${millis(percentile(responses, 0.95))};response_ms_max=${millis(responses.max())};" +
            "six_response_run_ms_p50=${millis(percentile(runs, 0.50))};" +
            "six_response_run_ms_p95=${millis(percentile(runs, 0.95))};six_response_run_ms_max=${millis(runs.max())};" +
            "partial_records=${samples.sumOf { it.partial }};dropped_records=${samples.sumOf { it.dropped }};" +
            "missing_required_shapes=${samples.sumOf { it.missingShape }};records=${samples.sumOf { it.records }};" +
            "output_bytes_total=${bytes.sum()};output_bytes_p95=${percentile(bytes, 0.95)};output_bytes_max=${bytes.max()};" +
            "thread_CPU_ms_total=${sumAvailable(samples.map { it.cpuNanos })?.let(::millis) ?: "UNAVAILABLE"};" +
            "thread_CPU_available_runs=${samples.count { it.cpuNanos != null }};" +
            "sampled_heap_high_water_bytes=${heaps.maxOrNull()?.toString() ?: "UNAVAILABLE"};heap_samples=${heaps.size};" +
            "process_GC_count_delta=${sumAvailable(samples.map { it.gcCountDelta })?.toString() ?: "UNAVAILABLE"};" +
            "process_blocking_GC_count_delta=${sumAvailable(samples.map { it.blockingGcCountDelta })?.toString() ?: "UNAVAILABLE"}"
    }

    private fun threadCpuNanos(): Long? = runCatching { Debug.threadCpuTimeNanos().takeIf { it >= 0 } }.getOrNull()
    private fun heapUsed(): Long? = runCatching {
        Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()).takeIf { used -> used >= 0 } }
    }.getOrNull()
    private fun runtimeCounter(key: String): Long? = if (Build.VERSION.SDK_INT < 23) null else
        runCatching { Debug.getRuntimeStat(key)?.toLongOrNull()?.takeIf { it >= 0 } }.getOrNull()
    private fun counterDelta(before: Long?, after: Long?): Long? =
        if (before == null || after == null || after < before) null else after - before
    private fun sumAvailable(values: List<Long?>): Long? = if (values.any { it == null }) null else values.filterNotNull().sum()

    private fun percentile(values: List<Long>, fraction: Double): Long = values.sorted()[ceil(values.size * fraction).toInt().coerceAtLeast(1) - 1]
    private fun millis(nanos: Long): String = String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)
    private fun report(name: String, value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\n$name\n$value\n") })
    }
}
