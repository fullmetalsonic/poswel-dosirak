package com.fullmetalsonic.dosirak.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmupAlarmPlannerTest {
    private val target = 1_800_000_000_000L

    @Test fun exemptUsesExactlyOneWakeAtTargetMinus120Seconds() {
        val plan = requireNotNull(WarmupAlarmPlanner.plan(target, target - 300_000L, target - 300_000L, true))
        assertTrue(plan.warmup)
        assertEquals(target - 120_000L, plan.wakeEpochMillis)
        assertEquals(target, plan.targetWallEpochMillis)
    }

    @Test fun notExemptUsesColdTargetOnly() {
        val plan = requireNotNull(WarmupAlarmPlanner.plan(target, target - 300_000L, target - 300_000L, false))
        assertFalse(plan.warmup)
        assertEquals(target, plan.wakeEpochMillis)
        assertTrue(plan.readiness.contains("면제"))
    }

    @Test fun missedEarlyWakeFallsBackToColdTargetWithoutSecondEarlyAlarm() {
        val plan = requireNotNull(WarmupAlarmPlanner.plan(target, target - 119_999L, target - 119_999L, true))
        assertFalse(plan.warmup)
        assertEquals(target, plan.wakeEpochMillis)
    }

    @Test fun serverOffsetMapsLogicalTargetToDeviceWallDeadline() {
        val plan = requireNotNull(WarmupAlarmPlanner.plan(target, target - 300_000L, target - 297_000L, true))
        assertEquals(target, plan.targetEpochMillis)
        assertEquals(target + 3_000L, plan.targetWallEpochMillis)
        assertEquals(target - 117_000L, plan.wakeEpochMillis)
    }

    @Test fun newPastTargetIsNeverCaughtUp() {
        assertNull(WarmupAlarmPlanner.plan(target, target, target, true))
        assertNull(WarmupAlarmPlanner.plan(target, target + 1L, target + 1L, true))
    }

    @Test fun rebootRestoresOnlyFutureTargetAndUsesColdIfEarlyWakeWasMissed() {
        val restored = requireNotNull(WarmupAlarmPlanner.restore(target, target, target - 120_000L, target - 60_000L, true))
        assertFalse(restored.warmup)
        assertEquals(target, restored.wakeEpochMillis)
        assertNull(WarmupAlarmPlanner.restore(target, target, target - 120_000L, target, true))
    }

    @Test fun rebootRespectsCurrentBatteryExemptionAndKeepsFutureEarlyWakeOnlyWhenEligible() {
        assertTrue(requireNotNull(WarmupAlarmPlanner.restore(target, target, target - 120_000L, target - 300_000L, true)).warmup)
        val restricted = requireNotNull(WarmupAlarmPlanner.restore(target, target, target - 120_000L, target - 300_000L, false))
        assertFalse(restricted.warmup)
        assertEquals(target, restricted.wakeEpochMillis)
    }

    @Test fun activeFlightIsBoundedAndDoesNotSurviveElapsedClockResetOrGenerationChange() {
        assertTrue(AlarmDispatchGuard.activeFlight(7, 7, 1_000L, 601_000L, 2_000L))
        assertFalse(AlarmDispatchGuard.activeFlight(7, 7, 1_000L, 601_000L, 601_000L))
        assertFalse(AlarmDispatchGuard.activeFlight(7, 7, 1_000L, 601_000L, 999L))
        assertFalse(AlarmDispatchGuard.activeFlight(7, 8, 1_000L, 601_000L, 2_000L))
        assertFalse(AlarmDispatchGuard.activeFlight(7, 7, 1_000L, 601_001L, 2_000L))
    }

    @Test fun consumedTargetBlocksSameAlarmButDoesNotBlockAnotherDateOrGeneration() {
        assertTrue(AlarmDispatchGuard.consumedTarget(target, 7, target, 7))
        assertFalse(AlarmDispatchGuard.consumedTarget(target, 8, target, 7))
        assertFalse(AlarmDispatchGuard.consumedTarget(target + 86_400_000L, 7, target, 7))
    }
}
