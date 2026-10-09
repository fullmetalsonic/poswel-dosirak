package com.fullmetalsonic.dosirak.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ServerOrderClockTest {
    private val date = LocalDate.of(2026, 10, 9)
    private val zone = ZoneId.of("Asia/Seoul")
    private val target = date.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()

    private class Wall(var value: Long) : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(value)
        override fun getZone(): ZoneId = ZoneId.of("Asia/Seoul")
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }

    private inner class Setup(offset: Long = 0) {
        var elapsed = 10_000L
        val wall = Wall(target + offset)
        val clock = ServerOrderClock(wall) { elapsed }
        fun sample(server: Long = target, rtt: Long = 200) = ServerTimeSample(server, elapsed - rtt, elapsed, wall.value)
        fun advance(millis: Long) { elapsed += millis; wall.value += millis }
    }

    @Test fun phoneAheadOrBehindCannotChangeServerBounds() {
        listOf(-30_000L, 30_000L).forEach { offset ->
            val s = Setup(offset)
            assertTrue(s.clock.accept(s.sample()))
            assertEquals(target, s.clock.bounds()!!.earliestEpochMillis)
            assertEquals(target + 1_200, s.clock.bounds()!!.latestEpochMillis)
            assertEquals(target + 600, s.clock.instant().toEpochMilli())
            assertNull(s.clock.purchaseProblem(date, LocalTime.of(6, 0)))
        }
    }

    @Test fun beforeOpeningWaitsForEarliestBoundNotEstimatedOrPhoneTime() {
        val s = Setup(30_000)
        assertTrue(s.clock.accept(s.sample(target - 1)))
        assertNotNull(s.clock.purchaseProblem(date, LocalTime.of(6, 0)))
        assertEquals(1L, s.clock.delayUntil(date, LocalTime.of(6, 0)))
        s.advance(1)
        assertNull(s.clock.purchaseProblem(date, LocalTime.of(6, 0)))
        assertEquals(0L, s.clock.delayUntil(date, LocalTime.of(6, 0)))
    }

    @Test fun savedStartAndClosingBoundaryUseEntireUncertaintyRange() {
        val s = Setup()
        assertTrue(s.clock.accept(s.sample()))
        assertNotNull(s.clock.purchaseProblem(date, LocalTime.of(6, 1)))
        val nearClosing = Setup()
        val close = date.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        nearClosing.wall.value = close - 1_200
        assertTrue(nearClosing.clock.accept(nearClosing.sample(close - 1_200)))
        assertNotNull(nearClosing.clock.purchaseProblem(date, LocalTime.of(6, 0)))
        val justBefore = Setup()
        justBefore.wall.value = close - 1_201
        assertTrue(justBefore.clock.accept(justBefore.sample(close - 1_201)))
        assertNull(justBefore.clock.purchaseProblem(date, LocalTime.of(6, 0)))
    }

    @Test fun noSampleAndExpiredSampleNeverAuthorizePurchaseThoughDisplayHasWallFallback() {
        val s = Setup()
        assertNotNull(s.clock.purchaseProblem(date, LocalTime.of(6, 0)))
        assertEquals(s.wall.instant(), s.clock.instant()); assertNull(s.clock.delayUntil(date, LocalTime.of(6, 0)))
        assertTrue(s.clock.accept(s.sample()))
        s.advance(180_001)
        assertNull(s.clock.bounds()); assertNotNull(s.clock.purchaseProblem(date, LocalTime.of(6, 0)))
        assertEquals(s.wall.instant(), s.clock.instant())
    }

    @Test fun twoSecondWallJumpToleranceAndMonotonicRegressionAreFailClosed() {
        val s = Setup(); assertTrue(s.clock.accept(s.sample()))
        s.wall.value += 2_000
        assertNotNull(s.clock.bounds())
        s.wall.value++
        assertNull(s.clock.bounds())
        val regressed = Setup(); assertTrue(regressed.clock.accept(regressed.sample()))
        regressed.elapsed--
        assertNull(regressed.clock.bounds()); assertNotNull(regressed.clock.purchaseProblem(date, LocalTime.of(6, 0)))
    }

    @Test fun excessiveRttUncertaintyFutureResponseAndInvalidEpochAreRejected() {
        listOf(1_001L, 2_000L, 2_001L, -1L).forEach { rtt ->
            val s = Setup(); assertFalse(s.clock.accept(s.sample(rtt = rtt))); assertNull(s.clock.bounds())
        }
        val s = Setup()
        assertFalse(s.clock.accept(s.sample().copy(responseFinishedElapsedMillis = s.elapsed + 1)))
        assertFalse(s.clock.accept(s.sample(server = 0)))
        assertFalse(s.clock.accept(s.sample(server = Long.MAX_VALUE)))
        assertTrue(s.clock.accept(s.sample(rtt = 1_000)))
        assertEquals(2_000L, s.clock.bounds()!!.let { it.latestEpochMillis - it.earliestEpochMillis })
    }

    @Test fun conflictingFreshSampleInvalidatesRatherThanMovingPurchaseTime() {
        val s = Setup(); assertTrue(s.clock.accept(s.sample()))
        s.advance(100)
        assertFalse(s.clock.accept(s.sample(target + 20_000)))
        assertNull(s.clock.bounds()); assertNotNull(s.clock.purchaseProblem(date, LocalTime.of(6, 0)))
    }

    @Test fun consistentNewSampleAndAlternateDisplayZonePreserveSharedClock() {
        val s = Setup(); assertTrue(s.clock.accept(s.sample()))
        s.advance(1_000)
        assertTrue(s.clock.accept(s.sample(target + 1_000)))
        val utc = s.clock.withZone(ZoneId.of("UTC"))
        assertEquals(ZoneId.of("UTC"), utc.zone); assertEquals(s.clock.instant(), utc.instant())
        s.advance(50)
        assertEquals(s.clock.instant(), utc.instant())
    }

    @Test fun serverDateMismatchCannotUseCorrectDeviceDateAsFallback() {
        val s = Setup()
        s.wall.value = target - 86_400_000
        assertTrue(s.clock.accept(s.sample(target - 86_400_000)))
        assertNotNull(s.clock.purchaseProblem(date, LocalTime.of(6, 0)))
    }

    @Test fun initialOffsetBeyondFiveMinutesRequiresDeviceOrSiteTimeCorrection() {
        listOf(-300_001L, 300_001L).forEach { offset ->
            val s = Setup(offset)
            assertFalse(s.clock.accept(s.sample())); assertNull(s.clock.bounds())
            assertNotNull(s.clock.purchaseProblem(date, LocalTime.of(6, 0)))
        }
        listOf(-300_000L, 300_000L).forEach { offset ->
            val s = Setup(offset); assertTrue(s.clock.accept(s.sample()))
        }
    }
}
