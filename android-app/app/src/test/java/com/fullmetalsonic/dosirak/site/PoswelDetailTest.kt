package com.fullmetalsonic.dosirak.site

import com.fullmetalsonic.dosirak.diagnostics.*
import com.fullmetalsonic.dosirak.domain.*
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.zip.ZipFile

class PoswelDetailTest {
    private lateinit var server: MockWebServer
    private val date = LocalDate.of(2026, 10, 9)
    private val plan = OrderPlan(date, 2, LocalTime.of(6, 0), DatePolicy.MANUAL, ReservationReason.NONE)
    private fun Gson() = GsonBuilder().registerTypeAdapter(LocalDate::class.java,
        JsonSerializer<LocalDate> { value, _, _ -> JsonPrimitive(value.toString()) }).create()
    private class Sink(var enabled: Boolean = true) : DiagnosticSink {
        val observations = mutableListOf<Pair<DetailCapture, SafeDetailObservation>>()
        private val process = DiagnosticRun.newId()
        private val segment = DiagnosticRun.newId()
        override fun record(event: DiagnosticEvent) = Unit
        override fun captureDetail(runId: String, requestId: String?, requestOrdinal: Int?, parentRequestId: String?): DetailCapture? =
            if (enabled) DetailCapture(process, runId, requestId, requestOrdinal, parentRequestId, segment, 0) else null
        override fun isDetailCaptureCurrent(capture: DetailCapture): Boolean = enabled
        override fun recordDetail(capture: DetailCapture, detail: SafeDetailObservation) { if (enabled) observations.add(capture to detail) }
    }
    @Before fun before() { server = MockWebServer().apply { start() }; DiagnosticRun.threadLocal.set(DiagnosticRun.newId()) }
    @After fun after() { DiagnosticRun.threadLocal.remove(); server.shutdown() }
    private fun fixture(name: String): String = javaClass.getResource("/site/$name.html")!!.readText()
    private fun client(sink: DiagnosticSink): PoswelClient = PoswelClient(OkHttpClient(), server.url("/"), true,
        Clock.fixed(Instant.parse("2026-10-08T21:00:00Z"), ZoneId.of("Asia/Seoul")), diagnostics = sink)
    private fun enqueue(body: String, status: Int = 200) { server.enqueue(MockResponse().setResponseCode(status).setBody(body)) }
    private fun cart(): String = """{"code":"0000","data":[{"me_no":"963","me_date":"10월09일(금)","me_menu":"피자돈까스&amp;소스 + 샐러드","me_quantity":"2","me_price":"10000","PRIVATE_KEY":"PRIVATE_VALUE"}]}"""
    private fun checkout(gateway: PoswelClient): CheckoutSnapshot {
        enqueue(fixture("main")); enqueue("""{"code":"0000","PRIVATE_TOKEN":"PRIVATE_SECRET"}""")
        enqueue(cart()); enqueue(fixture("checkout"))
        return gateway.createCheckout(plan)
    }
    private fun failure(code: String, action: () -> Unit): SiteException {
        try { action() } catch (error: SiteException) { assertEquals(code, error.code); return error }
        throw AssertionError("Expected $code")
    }

    @Test fun onAndOffHaveIdenticalCheckoutSubmitAndHistoryWithNoAdditionalRequests() {
        val snapshots = mutableListOf<CheckoutSnapshot>()
        val receipts = mutableListOf<SubmitReceipt>()
        val histories = mutableListOf<List<SiteOrder>>()
        val captured = mutableListOf<Pair<DetailCapture, SafeDetailObservation>>()
        for (enabled in listOf(false, true)) {
            val sink = Sink(enabled)
            val gateway = client(sink)
            val snapshot = checkout(gateway)
            snapshots.add(snapshot)
            enqueue("<script>alert('주문이 완료 되었습니다.');location.href='/order.list.php';</script>")
            receipts.add(gateway.submit(snapshot))
            enqueue(fixture("history")); histories.add(gateway.readOrders(date))
            assertEquals(if (enabled) 12 else 6, server.requestCount)
            repeat(6) { server.takeRequest() }
            if (!enabled) assertTrue(sink.observations.isEmpty())
            captured.addAll(sink.observations)
        }
        assertEquals(snapshots[0], snapshots[1]); assertEquals(receipts[0], receipts[1]); assertEquals(histories[0], histories[1])
        val http = captured.filter { it.second.kind == DetailKind.HTTP }
        assertEquals(6, http.size)
        assertEquals((1..6).toList(), http.map { it.first.requestOrdinal })
        assertEquals(6, http.map { it.first.requestId }.toSet().size)
        assertEquals(1, http.map { it.first.runId }.toSet().size)
        assertTrue(captured.any { it.second.json != null })
        assertTrue(captured.any { it.second.history?.rows?.any { row -> row.cancelHandler == SafeHistoryHandler.JS_ORDER_CXL } == true })
        assertTrue(captured.any { it.second.business?.menuMatches == true })
        assertTrue(captured.any { it.second.completionAlertMatched == true && it.second.coverage == DetailCoverage.HTTP_ONLY })
        val serialized = Gson().toJson(captured.map { it.second })
        listOf("PRIVATE_", "mock-account", "모의 배송지", "010-0000-0000", "피자돈까스").forEach {
            assertFalse("Leaked $it", serialized.contains(it))
        }
    }

    @Test fun parsedChallengeKeepsUnknownRequiredShapeAndOriginalGuardWithoutLoginPost() {
        val sink = Sink()
        enqueue(fixture("login").replace("</form>", "<input name='otp' required value='PRIVATE_OTP'></form>"))
        failure("ADDITIONAL_AUTH") { client(sink).ensureSession(Credentials("PRIVATE_ACCOUNT", "PRIVATE_PASSWORD")) }
        assertEquals(1, server.requestCount)
        assertTrue(sink.observations.any { it.second.form?.fields?.any { field -> field.unknownFieldSlot != null && field.required } == true })
        assertTrue(sink.observations.any { it.second.kind == DetailKind.GUARD && it.second.code == DiagnosticCode.ADDITIONAL_AUTH })
        assertFalse(Gson().toJson(sink.observations.map { it.second }).contains("PRIVATE_"))
    }

    @Test fun securityBodyStoppedBeforeExistingParserIsHonestlyHttpOnly() {
        val sink = Sink()
        enqueue(fixture("cloudbric-block"), 400)
        failure("SECURITY_BLOCK_CONTRACT") { client(sink).ensureSession(null) }
        assertEquals(1, server.requestCount)
        assertTrue(sink.observations.isNotEmpty())
        assertTrue(sink.observations.none { it.second.form != null || it.second.json != null })
        assertTrue(sink.observations.any { it.second.kind == DetailKind.GUARD && it.second.coverage == DetailCoverage.HTTP_ONLY })
    }

    @Test fun historyLoginAtHttp200RetainsSafeFormWithoutChangingFailureOrAddingRequests() {
        val captured = mutableListOf<SafeDetailObservation>()
        for (enabled in listOf(false, true)) {
            val sink = Sink(enabled)
            enqueue(fixture("login"))
            failure("LOGIN_REQUIRED") { client(sink).readOrders(date) }
            val request = server.takeRequest()
            assertEquals("POST", request.method); assertEquals("/order.list.php", request.path)
            if (!enabled) assertTrue(sink.observations.isEmpty())
            captured += sink.observations.map { it.second }
        }
        assertEquals(2, server.requestCount)
        assertTrue(captured.any { it.route == DiagnosticRoute.HISTORY && it.form?.fields?.any { field -> field.name == SafeFieldName.PWD } == true })
        assertTrue(captured.any { it.history?.tablePresent == false })
        assertFalse(Gson().toJson(captured).contains("PRIVATE_"))
    }

    @Test fun historyCloudbricAtHttp200KeepsExistingPreParserHttpOnlyBoundaryAndOneRequest() {
        for (enabled in listOf(false, true)) {
            val sink = Sink(enabled)
            enqueue(fixture("cloudbric-block"), 200)
            failure("SECURITY_BLOCK_CONTRACT") { client(sink).readOrders(date) }
            val request = server.takeRequest()
            assertEquals("POST", request.method); assertEquals("/order.list.php", request.path)
            assertTrue(sink.observations.none { it.second.form != null || it.second.json != null || it.second.history != null })
            if (enabled) assertTrue(sink.observations.any { it.second.coverage == DetailCoverage.HTTP_ONLY })
            else assertTrue(sink.observations.isEmpty())
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun malformedJsonPreservesUncertaintyAndDoesNotResend() {
        val sink = Sink()
        enqueue(fixture("main")); enqueue("PRIVATE_INVALID_JSON")
        assertTrue(failure("JSON_CONTRACT") { client(sink).createCheckout(plan) }.submissionPossible)
        assertEquals(2, server.requestCount)
        assertTrue(sink.observations.any { it.second.coverage == DetailCoverage.REACHED_NOT_EXTRACTED && it.second.code == DiagnosticCode.JSON_CONTRACT })
        assertFalse(Gson().toJson(sink.observations.map { it.second }).contains("PRIVATE_"))
    }

    @Test fun redirectsReceiveDistinctIdsAndParentReferenceWithoutLocationQuery() {
        val sink = Sink()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/?PRIVATE_QUERY=PRIVATE_VALUE"))
        enqueue(fixture("main"))
        client(sink).ensureSession(null)
        assertEquals(2, server.requestCount)
        val http = sink.observations.filter { it.second.kind == DetailKind.HTTP }
        assertEquals(2, http.size)
        assertEquals(http[0].first.requestId, http[1].first.parentRequestId)
        assertNotEquals(http[0].first.requestId, http[1].first.requestId)
        assertEquals(listOf(0, 1), http.map { it.second.redirectCount })
        assertFalse(Gson().toJson(http).contains("PRIVATE_"))
    }

    @Test fun diagnosticCaptureAndRecordFailuresNeverChangeTheOrderResult() {
        val sink = object : DiagnosticSink {
            override fun record(event: DiagnosticEvent) { throw IllegalStateException("PRIVATE_ERROR") }
            override fun captureDetail(runId: String, requestId: String?, requestOrdinal: Int?, parentRequestId: String?): DetailCapture? =
                throw IllegalStateException("PRIVATE_ERROR")
        }
        val gateway = client(sink)
        val snapshot = checkout(gateway)
        assertEquals(2, snapshot.quantity); assertEquals(10000L, snapshot.total); assertEquals(4, server.requestCount)
    }

    @Test fun realClientObservationsReachStoreAndCheckedZipWithoutChangingTheSixRequestOrderPath() = runBlocking {
        withTimeout(10_000) {
            val temporary = TemporaryFolder().apply { create() }
            try {
                val root = temporary.newFolder("client-store-zip")
                val preference = object : DiagnosticPreference {
                    var basic = false
                    var detail = false
                    var acknowledged = false
                    override fun readEnabled() = basic
                    override fun readDetailEnabled() = detail
                    override fun readDetailAcknowledged() = acknowledged
                    override fun writeEnabled(enabled: Boolean) = writeModes(enabled, false)
                    override fun writeModes(enabled: Boolean, detailEnabled: Boolean): Boolean {
                        basic = enabled; detail = enabled && detailEnabled
                        if (detail) acknowledged = true
                        return true
                    }
                }
                val store = DiagnosticStore(DiagnosticDisk(File(root, "analysis"), File(root, "analysis-share")), preference, "1.1.1")
                try {
                    store.setEnabled(true); store.setDetailEnabled(true)
                    val runId = DiagnosticRun.newId()
                    withContext(DiagnosticRun.contextElement(runId)) {
                        val gateway = client(store)
                        val checkout = checkout(gateway)
                        assertEquals(date, checkout.date); assertEquals(2, checkout.quantity)
                        assertEquals(5000L, checkout.unitPrice); assertEquals(10000L, checkout.total)
                        enqueue("<script>alert('주문이 완료 되었습니다.');location.href='/order.list.php';</script>")
                        val receipt = gateway.submit(checkout)
                        assertEquals(200, receipt.responseStatus); assertEquals("주문이 완료 되었습니다.", receipt.alert)
                        enqueue(fixture("history").replace("<body>", "<body><div>" + "<i>PRIVATE_NOISE</i>".repeat(600) + "</div>"))
                        val orders = gateway.readOrders(date)
                        assertNotNull(orders.singleOrNull { it.date == date && it.quantity == 2 && it.total == 10000L })
                    }
                    assertEquals(6, server.requestCount)
                    val requests = List(6) { server.takeRequest() }
                    assertEquals(listOf("/", "/togobox/order.temp.php", "/togobox/get.order.temp.php", "/order.info.php",
                        "/togobox/order.reg.php", "/order.list.php"), requests.map { it.path })
                    assertEquals(1, requests.count { it.path == "/togobox/order.reg.php" && it.method == "POST" })
                    store.awaitIdle()
                    val snapshot = requireNotNull(store.snapshot())
                    assertTrue(snapshot.isZip)
                    val archive = requireNotNull(store.cacheSnapshot(snapshot))
                    ZipFile(archive).use { zip ->
                        val names = zip.entries().asSequence().map { it.name }.toSet()
                        assertTrue(names.containsAll(listOf("basic.jsonl", "details.jsonl", "coverage.json", "manifest.json", "checksums.sha256")))
                        fun text(name: String): String = zip.getInputStream(requireNotNull(zip.getEntry(name))).bufferedReader().use { it.readText() }
                        fun records(name: String) = text(name).lineSequence().filter { it.isNotBlank() }.map { JsonParser.parseString(it).asJsonObject }.toList()
                        val details = records("details.jsonl")
                        val basic = records("basic.jsonl")
                        assertTrue(details.isNotEmpty()); assertTrue(basic.isNotEmpty())
                        assertTrue(details.all { it.get("runId").asString == runId })
                        assertTrue(basic.all { it.get("runId").asString == runId })
                        val http = details.filter { it.getAsJsonObject("observation").get("kind").asString == "HTTP" }
                        assertEquals(6, http.size)
                        assertEquals((1..6).toList(), http.map { it.get("requestOrdinal").asInt })
                        val requestIds = http.map { it.get("requestId").asString }.toSet()
                        assertEquals(6, requestIds.size); assertTrue(requestIds.all(DiagnosticRun::valid))
                        assertEquals(listOf("HOME", "TEMPORARY_ORDER", "TEMPORARY_CART", "CHECKOUT", "SUBMISSION", "HISTORY"),
                            http.map { it.getAsJsonObject("observation").get("route").asString })
                        assertTrue(details.filter { it.has("requestId") }.all { it.get("requestId").asString in requestIds })
                        val observations = details.map { it.getAsJsonObject("observation") }
                        assertTrue(observations.none { it.has("history") })
                        val business = observations.filter { it.has("business") }
                        assertTrue(business.any { observation ->
                            val values = observation.getAsJsonObject("business")
                            observation.get("route").asString == "CHECKOUT" && values.get("targetDate").asString == date.toString() &&
                                values.get("quantity").asInt == 2 && values.get("unitPrice").asLong == 5000L && values.get("total").asLong == 10000L
                        })
                        assertTrue(business.any { observation ->
                            val values = observation.getAsJsonObject("business")
                            observation.get("route").asString == "SUBMISSION" && observation.get("completionAlertMatched").asBoolean &&
                                values.get("targetDate").asString == date.toString() && values.get("quantity").asInt == 2 && values.get("total").asLong == 10000L
                        })
                        assertTrue(business.any { observation -> observation.get("route").asString == "HISTORY" &&
                            observation.getAsJsonObject("business").get("targetDate").asString == date.toString() &&
                            observation.getAsJsonObject("business").get("resultCount").asInt == 3 })
                        val refs = details.filter { it.has("snapshotRef") }
                        assertTrue(refs.isNotEmpty())
                        val structures = refs.map { record ->
                            val ref = record.get("snapshotRef").asString
                            assertTrue(ref.startsWith("snapshots/")); assertTrue(ref in names)
                            assertTrue(record.get("requestId").asString in requestIds)
                            JsonParser.parseString(text(ref)).asJsonObject
                        }
                        assertTrue(structures.any { it.has("form") }); assertTrue(structures.any { it.has("json") })
                        val historySnapshot = structures.single { it.has("history") }.getAsJsonObject("history")
                        assertTrue(historySnapshot.get("tablePresent").asBoolean)
                        assertTrue(historySnapshot.get("allRowsScanned").asBoolean)
                        assertEquals(4, historySnapshot.get("rowsExamined").asInt)
                        val historyRows = historySnapshot.getAsJsonArray("rows").map { it.asJsonObject }
                        assertEquals("CANCELLED", historyRows[0].get("status").asString)
                        assertEquals("JS_ORDER_CXL", historyRows[1].get("cancelHandler").asString)
                        assertEquals(2, historyRows[1].get("quantity").asInt)
                        assertEquals(10000L, historyRows[1].get("total").asLong)
                        assertTrue(refs.any { it.get("snapshotRef").asString.startsWith("snapshots/HISTORY/") &&
                            it.get("structureFingerprint").asString.matches(Regex("[a-f0-9]{64}")) })
                        val historyRecord = refs.single { it.get("snapshotRef").asString.startsWith("snapshots/HISTORY/") }
                        val historyObservation = historyRecord.getAsJsonObject("observation")
                        assertEquals("COMPLETE", historyObservation.get("coverage").asString)
                        assertFalse(historySnapshot.get("partial").asBoolean)
                        assertFalse(historyObservation.getAsJsonArray("omissions").any { it.asString == "NODE_LIMIT" })
                        assertTrue(historyObservation.get("visitedNodes").asInt < 500)
                        val pageObservation = details.single { it.get("requestId") == historyRecord.get("requestId") &&
                            it.getAsJsonObject("observation").get("kind").asString == "STRUCTURE" && !it.has("snapshotRef") }
                            .getAsJsonObject("observation")
                        assertEquals("PARTIAL", pageObservation.get("coverage").asString)
                        assertTrue(pageObservation.getAsJsonArray("omissions").any { it.asString == "NODE_LIMIT" })
                        assertEquals(500, pageObservation.get("visitedNodes").asInt)
                        assertTrue(historyObservation.get("extractionNanos").asLong <= pageObservation.get("extractionNanos").asLong)
                        val coverage = JsonParser.parseString(text("coverage.json")).asJsonObject
                        val run = coverage.getAsJsonArray("runs").single { it.asJsonObject.get("runId").asString == runId }.asJsonObject
                        // A direct client test has no runtime RUN_FINISHED; the export must keep this gap visible.
                        assertTrue(run.get("unclosed").asBoolean)
                        val routes = run.getAsJsonArray("routes").map { it.asJsonObject }
                        val coveredRequests = routes.flatMap { it.getAsJsonArray("requests").map { item -> item.asJsonObject } }
                        assertEquals(requestIds, coveredRequests.map { it.get("requestId").asString }.toSet())
                        val submission = routes.single { it.get("route").asString == "SUBMISSION" }.getAsJsonArray("requests").single().asJsonObject
                        assertEquals("HTTP_ONLY", submission.get("coverage").asString)
                        assertTrue(submission.getAsJsonArray("omissions").any { it.asString == "STRUCTURE_NOT_EXTRACTED" })
                        assertTrue(routes.filter { it.get("route").asString in setOf("LOGIN", "PROFILE") }
                            .all { it.get("coverage").asString == "NOT_REACHED" })
                        observations.filter { it.get("kind").asString == "STRUCTURE" && it.get("coverage").asString != "COMPLETE" }.forEach { partial ->
                            assertTrue(partial.getAsJsonArray("omissions").size() > 0)
                        }
                        assertEquals(store.status.value.dropped, coverage.get("basicDropped").asLong)
                        assertEquals(store.status.value.detailDropped, coverage.get("detailQueueDropped").asLong +
                            coverage.get("detailStorageDropped").asLong + coverage.get("corruptRecords").asLong)
                        assertFalse(JsonParser.parseString(text("manifest.json")).asJsonObject.get("complete").asBoolean)
                        val allText = names.joinToString("\n", transform = ::text)
                        listOf("PRIVATE_", "mock-account", "mock-course", "mock-location", "mock-section", "모의 배송지", "모의 상세",
                            "010-0000-0000", "피자돈까스", "모의 메뉴", "다른 수량", "<html", "<script", "\"963\"", "\"827\"").forEach {
                            assertFalse("Leaked synthetic fixture marker: $it", allText.contains(it))
                        }
                        val checksums = text("checksums.sha256").lineSequence().filter { it.isNotBlank() }.toList()
                        val checkedNames = checksums.map { it.split("  ", limit = 2)[1] }.toSet()
                        assertEquals(names - "checksums.sha256", checkedNames)
                        checksums.forEach { line ->
                            val (expected, name) = line.split("  ", limit = 2)
                            val actual = MessageDigest.getInstance("SHA-256").digest(zip.getInputStream(zip.getEntry(name)).use { it.readBytes() })
                                .joinToString("") { "%02x".format(it) }
                            assertEquals(name, expected, actual)
                        }
                    }
                } finally { store.close() }
            } finally { temporary.delete() }
        }
    }
}
