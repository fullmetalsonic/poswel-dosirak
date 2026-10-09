package com.fullmetalsonic.dosirak.platform

import com.fullmetalsonic.dosirak.domain.BackgroundCheckMode
import java.time.Instant
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundCheckTimingTest {
    @Test fun hourlyUsesOneHourOfMonotonicTime() {
        val now = BackgroundCheckNow(Instant.parse("2026-12-31T23:30:00Z").toEpochMilli(), 73_000L)
        val next = BackgroundCheckTiming.next(BackgroundCheckMode.HOURLY, LocalTime.of(5, 50), now)
        assertEquals(now.epochMillis + 3_600_000L, next.epochMillis)
        assertEquals(now.elapsedMillis + 3_600_000L, next.elapsedMillis)
    }

    @Test fun dailyUsesKoreaTimeAndKeepsSeconds() {
        val now = BackgroundCheckNow(Instant.parse("2026-10-08T20:40:00Z").toEpochMilli(), 10L)
        val next = BackgroundCheckTiming.next(BackgroundCheckMode.DAILY, LocalTime.of(5, 50, 37), now)
        assertEquals(Instant.parse("2026-10-08T20:50:37Z").toEpochMilli(), next.epochMillis)
    }

    @Test fun dailyAtOrAfterCheckTimeMovesToTomorrow() {
        listOf("2026-10-08T20:50:00Z", "2026-10-08T20:50:01Z").forEach { instant ->
            val next = BackgroundCheckTiming.next(BackgroundCheckMode.DAILY, LocalTime.of(5, 50),
                BackgroundCheckNow(Instant.parse(instant).toEpochMilli(), 0L))
            assertEquals(Instant.parse("2026-10-09T20:50:00Z").toEpochMilli(), next.epochMillis)
        }
    }

    @Test fun dailyHandlesLeapDayAndYearEnd() {
        mapOf("2024-02-28T20:51:00Z" to "2024-02-29T20:50:00Z",
            "2026-12-31T20:51:00Z" to "2027-01-01T20:50:00Z").forEach { (input, expected) ->
            val next = BackgroundCheckTiming.next(BackgroundCheckMode.DAILY, LocalTime.of(5, 50),
                BackgroundCheckNow(Instant.parse(input).toEpochMilli(), 0L))
            assertEquals(Instant.parse(expected).toEpochMilli(), next.epochMillis)
        }
    }

    @Test fun staleOrMissingTokensAndEarlyDispatchAreIgnored() {
        val now = BackgroundCheckNow(1000L, 1000L)
        assertFalse(BackgroundCheckDispatchGuard.isDue("token", "stale", BackgroundCheckMode.HOURLY, 1L, 1L, now))
        assertFalse(BackgroundCheckDispatchGuard.isDue(null, null, BackgroundCheckMode.HOURLY, 1L, 1L, now))
        assertFalse(BackgroundCheckDispatchGuard.isDue("", "", BackgroundCheckMode.HOURLY, 1L, 1L, now))
        assertFalse(BackgroundCheckDispatchGuard.isDue("token", "token", BackgroundCheckMode.HOURLY, 1L, 1001L, now))
        assertFalse(BackgroundCheckDispatchGuard.isDue("token", "token", BackgroundCheckMode.DAILY, 1001L, 1L, now))
        assertFalse(BackgroundCheckDispatchGuard.isDue("token", "token", null, 1L, 1L, now))
    }

    @Test fun hourlyDispatchIsIndependentOfWallClockChanges() {
        assertTrue(BackgroundCheckDispatchGuard.isDue("t", "t", BackgroundCheckMode.HOURLY,
            999_999L, 1000L, BackgroundCheckNow(500L, 1000L)))
        assertFalse(BackgroundCheckDispatchGuard.isDue("t", "t", BackgroundCheckMode.HOURLY,
            1L, 1000L, BackgroundCheckNow(999_999L, 999L)))
        assertTrue(BackgroundCheckDispatchGuard.isDue("t", "t", BackgroundCheckMode.DAILY,
            1000L, 999_999L, BackgroundCheckNow(1000L, 1L)))
    }
}
