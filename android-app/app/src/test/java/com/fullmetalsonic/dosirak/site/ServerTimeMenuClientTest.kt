package com.fullmetalsonic.dosirak.site

import com.fullmetalsonic.dosirak.domain.DatePolicy
import com.fullmetalsonic.dosirak.domain.OrderPlan
import com.fullmetalsonic.dosirak.domain.ReservationReason
import com.fullmetalsonic.dosirak.domain.ServerOrderClock
import com.fullmetalsonic.dosirak.domain.ServerTimeSample
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class ServerTimeMenuClientTest {
    private lateinit var server: MockWebServer
    private var elapsed = 10_000L
    private val wall = Instant.parse("2026-10-08T21:00:01Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Seoul")
    private val date = LocalDate.of(2026, 10, 9)
    private val plan = OrderPlan(date, 2, LocalTime.of(6, 0), DatePolicy.MANUAL, ReservationReason.NONE)
    private val http = OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).build()

    @Before fun setup() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }
    private fun fixture(name: String) = javaClass.getResource("/site/$name.html")!!.readText()
    private fun client(clock: Clock = Clock.fixed(Instant.ofEpochMilli(wall), zone),
        mono: () -> Long = { elapsed }, wallClock: () -> Long = { wall }): PoswelClient =
        PoswelClient(http, server.url("/"), true, clock, mono, wallClock)
    private fun enqueue(html: String) { server.enqueue(MockResponse().setBody(html)) }
    private fun mainWithTime(time: String = "2026-10-09 06:00:01") = fixture("main") + "<script>var servertimeinfo='$time';</script>"
    private fun enqueueCartAndCheckout() {
        enqueue("{\"code\":\"0000\"}")
        enqueue("""{"code":"0000","data":[{"me_no":"963","me_date":"10월09일(금)","me_menu":"피자돈까스&소스 + 샐러드","me_quantity":"2","me_price":"10000"}]}""")
        enqueue(fixture("checkout"))
    }
    private fun expect(code: String, block: () -> Unit): SiteException {
        val error = assertThrows(SiteException::class.java) { block() }
        assertEquals(code, error.code)
        return error
    }

    @Test fun probeRecordsActualMonotonicEndpointsAndCompletedWallWithoutDateFallback() {
        var sampleIndex = 0
        val times = listOf(10_000L, 10_050L)
        val gateway = client(mono = { times[sampleIndex++] }, wallClock = { wall + 50 })
        enqueue(mainWithTime())
        val sample = gateway.probeServerTime()
        assertEquals(wall, sample.serverEpochMillis)
        assertEquals(10_000L, sample.requestStartedElapsedMillis)
        assertEquals(10_050L, sample.responseFinishedElapsedMillis)
        assertEquals(wall + 50, sample.deviceWallMillis)
        val request = server.takeRequest(); assertEquals("GET", request.method); assertEquals("/", request.path)
        assertTrue(request.getHeader("Cache-Control")!!.contains("no-cache")); assertTrue(request.getHeader("Cache-Control")!!.contains("no-store"))
    }

    @Test fun probeRejectsMissingTimeEvenWithHttpDateHeader() {
        server.enqueue(MockResponse().setHeader("Date", "Fri, 09 Oct 2026 06:00:01 GMT").setBody(fixture("main")))
        expect("TIME_UNAVAILABLE") { client().probeServerTime() }
    }

    @Test fun probeHasTwoSecondNetworkDeadlineAcrossHeadersAndBodyAndNeverPosts() {
        val longTimeoutHttp = http.newBuilder().readTimeout(5, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
        val gateway = PoswelClient(longTimeoutHttp, server.url("/"), true,
            Clock.fixed(Instant.ofEpochMilli(wall), zone), { elapsed }, { wall })
        for (delayHeaders in listOf(true, false)) {
            val response = MockResponse().setBody(mainWithTime())
            if (delayHeaders) response.setHeadersDelay(3, TimeUnit.SECONDS) else response.setBodyDelay(3, TimeUnit.SECONDS)
            server.enqueue(response)
            val started = System.nanoTime()
            assertFalse(expect("NETWORK") { gateway.probeServerTime() }.submissionPossible)
            val elapsedReal = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("Probe ended too late: $elapsedReal ms", elapsedReal < 3_000)
            assertTrue("Longer read timeout must not replace the two-second call deadline: $elapsedReal ms", elapsedReal >= 1_500)
            val request = server.takeRequest(); assertEquals("GET", request.method); assertEquals("/", request.path)
        }
        assertEquals(2, server.requestCount)
        assertEquals(TimeUnit.SECONDS.toMillis(30).toInt(), longTimeoutHttp.callTimeoutMillis)
    }

    @Test fun timeProbeRejectsRedirectAfterOneRootGetInsteadOfStartingAnotherDeadline() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/"))
        expect("TIME_UNAVAILABLE") { client().probeServerTime() }
        assertEquals(1, server.requestCount)
        val request = server.takeRequest(); assertEquals("GET", request.method); assertEquals("/", request.path)
    }

    @Test fun probeRequiresAuthenticatedSuccessfulResponse() {
        enqueue(fixture("login") + "<script>var servertimeinfo='2026-10-09 06:00:01';</script>")
        expect("LOGIN_REQUIRED") { client().probeServerTime() }
        server.enqueue(MockResponse().setResponseCode(503).setBody(mainWithTime()))
        expect("HTTP") { client().probeServerTime() }
        enqueue("<script>var servertimeinfo='2026-10-09 06:00:01';</script>")
        expect("TIME_UNAVAILABLE") { client().probeServerTime() }
        server.enqueue(MockResponse().setResponseCode(206).setBody(mainWithTime()))
        expect("TIME_UNAVAILABLE") { client().probeServerTime() }
    }

    @Test fun explicitCacheAgeOrHitCannotSupplyTimeOrMenu() {
        for ((name, value) in listOf("Age" to "0", "X-Cache" to "HIT", "CF-Cache-Status" to "STALE", "Warning" to "110 proxy stale")) {
            server.enqueue(MockResponse().setHeader(name, value).setBody(mainWithTime()))
            expect("CACHED_RESPONSE") { client().probeServerTime() }
        }
        server.enqueue(MockResponse().setHeader("Age", "1").setBody(fixture("main")))
        expect("CACHED_RESPONSE") { client().loadMenu(plan, 3, 4) }
    }

    @Test fun loadedMenuIsBoundAndConsumedWithoutSecondHomeGet() {
        val gateway = client(); enqueue(fixture("main"))
        val menu = gateway.loadMenu(plan, 3, 7)
        assertEquals(date, menu.date); assertEquals(2, menu.quantity); assertEquals(3L, menu.generation); assertEquals(7L, menu.accountGeneration)
        enqueueCartAndCheckout()
        val snapshot = gateway.createCheckout(plan, menu) {}
        assertEquals(10000L, snapshot.total); assertEquals(4, server.requestCount)
        assertEquals("/", server.takeRequest().path)
        val temp = server.takeRequest(); assertEquals("/togobox/order.temp.php", temp.path)
        assertTrue(temp.body.readUtf8().contains("%26"))
        expect("MENU_USED") { gateway.createCheckout(plan, menu) {} }
        assertEquals(4, server.requestCount)
    }

    @Test fun oldWrongClientWrongQuantityOrUntrustedSnapshotCannotReachMutation() {
        val gateway = client(); enqueue(fixture("main"))
        val menu = gateway.loadMenu(plan, 3, 7)
        expect("MENU_UNVERIFIED") { client().createCheckout(plan, menu) {} }
        expect("MENU_UNVERIFIED") { gateway.createCheckout(plan.copy(quantity = 1), menu) {} }
        val untrusted = object : MenuSnapshot {
            override val date = plan.date; override val quantity = plan.quantity
            override val generation = 3L; override val accountGeneration = 7L
        }
        expect("MENU_UNVERIFIED") { gateway.createCheckout(plan, untrusted) {} }
        elapsed += 10_001
        assertFalse(expect("MENU_EXPIRED") { gateway.createCheckout(plan, menu) {} }.submissionPossible)
        assertEquals(1, server.requestCount)
    }

    @Test fun refreshingMenuInvalidatesOldGenerationAndAccountSnapshot() {
        val gateway = client(); enqueue(fixture("main")); val original = gateway.loadMenu(plan, 3, 7)
        enqueue(fixture("main")); val updated = gateway.loadMenu(plan, 4, 8)
        expect("MENU_USED") { gateway.createCheckout(plan, original) {} }
        assertEquals(4L, updated.generation); assertEquals(8L, updated.accountGeneration)
        assertEquals(2, server.requestCount)
    }

    @Test fun cancellationAfterHomeCompletesPreventsTemporaryPost() {
        val gateway = client(); enqueue(fixture("main"))
        val menu = gateway.loadMenu(plan, 3, 7)
        assertThrows(CancellationException::class.java) {
            gateway.createCheckout(plan, menu) { throw CancellationException("synthetic cancelled operation") }
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationAfterCheckoutCompletesPreventsFinalPost() {
        val gateway = client(); enqueue(fixture("main")); val menu = gateway.loadMenu(plan, 3, 7)
        enqueueCartAndCheckout(); val checkout = gateway.createCheckout(plan, menu) {}
        assertThrows(CancellationException::class.java) { gateway.submit(checkout) { throw CancellationException("synthetic cancelled operation") } }
        assertEquals(4, server.requestCount)
    }

    @Test fun lastMomentMenuExpiryOrSettingsGuardStopsTemporaryPost() {
        val gateway = client(); enqueue(fixture("main")); val menu = gateway.loadMenu(plan, 3, 7)
        assertFalse(expect("MENU_EXPIRED") { gateway.createCheckout(plan, menu) { elapsed += 10_001 } }.submissionPossible)
        assertEquals(1, server.requestCount)
        enqueue(fixture("main")); val next = gateway.loadMenu(plan, 3, 7)
        assertFalse(expect("SETTINGS_CHANGED") { gateway.createCheckout(plan, next) { throw SiteException("SETTINGS_CHANGED", "changed") } }.submissionPossible)
        assertEquals(2, server.requestCount)
    }

    @Test fun sharedServerClockDrivesDateDespiteDeviceDayAndRequiresValidSample() {
        val deviceUtc = Clock.fixed(Instant.ofEpochMilli(wall), ZoneId.of("UTC"))
        val shared = ServerOrderClock(deviceUtc) { elapsed }
        assertTrue(shared.accept(ServerTimeSample(wall, elapsed, elapsed, deviceUtc.millis())))
        assertNotEquals(LocalDate.now(deviceUtc), LocalDate.now(shared))
        val gateway = client(shared, wallClock = { deviceUtc.millis() })
        enqueue(fixture("main")); gateway.loadMenu(plan, 3, 7)
        assertEquals(1, server.requestCount)
        val unsynced = ServerOrderClock(deviceUtc) { elapsed }
        expect("SERVER_TIME_GATE") { client(unsynced).loadMenu(plan, 3, 7) }
        assertEquals(1, server.requestCount)
    }

    @Test fun allowedThirtySecondOffsetAcrossMidnightUsesServerDateButDoesNotPermitEarlyOrder() {
        val serverMidnight = date.atStartOfDay(zone).toInstant().toEpochMilli() + 20_000
        val devicePriorDay = Clock.fixed(Instant.ofEpochMilli(serverMidnight - 30_000), zone)
        val shared = ServerOrderClock(devicePriorDay) { elapsed }
        assertTrue(shared.accept(ServerTimeSample(serverMidnight, elapsed, elapsed, devicePriorDay.millis())))
        assertEquals(date.minusDays(1), LocalDate.now(devicePriorDay))
        assertEquals(date, LocalDate.now(shared))
        expect("SERVER_TIME_GATE") { client(shared, wallClock = { devicePriorDay.millis() }).loadMenu(plan, 3, 7) }
        assertEquals(0, server.requestCount)
    }

    @Test fun sharedClockExpiredAfterMenuCannotReachTemporaryPost() {
        val shared = ServerOrderClock(Clock.fixed(Instant.ofEpochMilli(wall), zone)) { elapsed }
        assertTrue(shared.accept(ServerTimeSample(wall, elapsed, elapsed, wall)))
        val gateway = client(shared); enqueue(fixture("main")); val menu = gateway.loadMenu(plan, 3, 7)
        assertFalse(expect("SERVER_TIME_GATE") { gateway.createCheckout(plan, menu) {
            // A malformed new sample invalidates the shared clock while the menu remains fresh.
            shared.accept(ServerTimeSample(wall, elapsed + 1, elapsed, wall))
        } }.submissionPossible)
        assertEquals(1, server.requestCount)
    }

    @Test fun sharedClockInvalidationBeforeFinalPostPreventsIt() {
        val shared = ServerOrderClock(Clock.fixed(Instant.ofEpochMilli(wall), zone)) { elapsed }
        assertTrue(shared.accept(ServerTimeSample(wall, elapsed, elapsed, wall)))
        val gateway = client(shared); enqueue(fixture("main")); val menu = gateway.loadMenu(plan, 3, 7)
        enqueueCartAndCheckout(); val checkout = gateway.createCheckout(plan, menu) {}
        expect("SERVER_TIME_GATE") { gateway.submit(checkout) { shared.accept(ServerTimeSample(wall, elapsed + 1, elapsed, wall)) } }
        assertEquals(4, server.requestCount)
    }
}
