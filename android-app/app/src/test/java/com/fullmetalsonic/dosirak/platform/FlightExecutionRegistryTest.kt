package com.fullmetalsonic.dosirak.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FlightExecutionRegistryTest {
    private val key = AlarmDispatchKey(LocalDate.of(2026, 10, 9), 4L)

    @Test fun persistedNonceAndLeaseWithoutAnInMemoryOwnerAreNotLive() {
        val registry = FlightExecutionRegistry("new-process")
        assertFalse(registry.live("token", "old-process", key, 1000L, 10L))
        assertFalse(registry.live("token", "new-process", key, 1000L, 10L))
        assertTrue(registry.reserve("token", key, 1000L, 10L))
        assertFalse(registry.live("token", "old-process", key, 1000L, 11L))
        assertTrue(registry.live("token", "new-process", key, 1000L, 11L))
        assertFalse(registry.live("token", "new-process", key.copy(generation = 5L), 1000L, 11L))
        assertFalse(registry.live("token", "new-process", key, 1001L, 11L))
    }

    @Test fun pendingReservationIsCasAndForegroundEvidenceIsSeparate() {
        val registry = FlightExecutionRegistry("process")
        assertTrue(registry.reserve("token", key, 1000L, 10L))
        assertFalse(registry.reserve("token", key, 1000L, 11L))
        assertFalse(registry.reserve("another-token", key, 1000L, 11L))
        assertFalse(registry.foregroundEntered("token"))
        assertFalse(registry.foreground("token", key.copy(generation = 5L), 11L))
        assertTrue(registry.foreground("token", key, 11L))
        assertTrue(registry.foregroundEntered("token"))
        assertTrue(registry.reserve("new-generation", key.copy(generation = 5L), 1000L, 11L))
    }

    @Test fun releaseAndBoundedExpiryRemoveOnlyTheExecutionOwner() {
        val registry = FlightExecutionRegistry("process")
        assertTrue(registry.reserve("token", key, 1000L, 10L))
        assertFalse(registry.live("token", "process", key, 1000L, 9L))
        assertTrue(registry.live("token", "process", key, 1000L, 10_009L))
        assertFalse(registry.live("token", "process", key, 1000L, 10_010L))
        assertFalse(registry.foreground("token", key, 10_010L))
        assertTrue(registry.reserve("replacement", key, 1000L, 10_010L))
        registry.release("replacement")
        assertFalse(registry.live("replacement", "process", key, 1000L, 10_011L))
        assertTrue(registry.reserve("replacement", key, 1000L, 10_011L))
    }

    @Test fun foregroundConfirmationExtendsLeaseOnceAndNewProcessCannotClaimIt() {
        val registry = FlightExecutionRegistry("process")
        assertTrue(registry.reserve("token", key, 1000L, 10L))
        assertTrue(registry.foreground("token", key, 9_010L))
        assertTrue(registry.live("token", "process", key, 1000L, 609_009L))
        assertTrue(registry.foreground("token", key, 609_009L))
        assertFalse(registry.live("token", "process", key, 1000L, 609_010L))
        assertFalse(registry.live("token", "new-process", key, 1000L, 9_011L))
        val restarted = FlightExecutionRegistry("new-process")
        assertFalse(restarted.live("token", "process", key, 1000L, 9_011L))
    }
}
