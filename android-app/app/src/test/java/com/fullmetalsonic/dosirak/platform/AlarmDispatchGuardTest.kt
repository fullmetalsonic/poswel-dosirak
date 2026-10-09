package com.fullmetalsonic.dosirak.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AlarmDispatchGuardTest {
    private val date = LocalDate.of(2026, 10, 9)

    @Test fun onlyCurrentGenerationCanDispatch() {
        assertTrue(AlarmDispatchGuard.isCurrent(7L, 7L))
        assertFalse(AlarmDispatchGuard.isCurrent(6L, 7L))
        assertFalse(AlarmDispatchGuard.isCurrent(8L, 7L))
        assertFalse(AlarmDispatchGuard.isCurrent(null, 7L))
        assertFalse(AlarmDispatchGuard.isCurrent(-1L, -1L))
    }

    @Test fun missingGenerationOrDateCannotCreateServiceJob() {
        assertNull(AlarmDispatchGuard.key(date, null))
        assertNull(AlarmDispatchGuard.key(date, -1L))
        assertNull(AlarmDispatchGuard.key(null, 7L))
        assertEquals(AlarmDispatchKey(date, 0L), AlarmDispatchGuard.key(date, 0L))
    }

    @Test fun sameDateNewGenerationIsNotLostBehindActiveOldGeneration() {
        val old = AlarmDispatchKey(date, 6L)
        val current = AlarmDispatchKey(date, 7L)
        assertTrue(AlarmDispatchGuard.shouldEnqueue(current, old, emptyList()))
        assertFalse(AlarmDispatchGuard.shouldEnqueue(old, old, emptyList()))
    }

    @Test fun pendingQueueDeduplicatesOnlyIdenticalDateAndGeneration() {
        val old = AlarmDispatchKey(date, 6L)
        val current = AlarmDispatchKey(date, 7L)
        assertTrue(AlarmDispatchGuard.shouldEnqueue(current, null, listOf(old)))
        assertFalse(AlarmDispatchGuard.shouldEnqueue(current, null, listOf(old, current)))
        assertTrue(AlarmDispatchGuard.shouldEnqueue(AlarmDispatchKey(date.plusDays(1), 7L), current, listOf(old)))
    }
}
