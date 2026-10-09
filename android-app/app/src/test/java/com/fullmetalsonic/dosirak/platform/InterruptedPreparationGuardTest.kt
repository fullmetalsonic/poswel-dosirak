package com.fullmetalsonic.dosirak.platform

import com.fullmetalsonic.dosirak.domain.AppSettings
import com.fullmetalsonic.dosirak.domain.DatePolicy
import com.fullmetalsonic.dosirak.domain.ExecutionRecord
import com.fullmetalsonic.dosirak.domain.ExecutionStatus
import com.fullmetalsonic.dosirak.domain.LiveScope
import com.fullmetalsonic.dosirak.domain.OrderPlan
import com.fullmetalsonic.dosirak.domain.ReservationReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class InterruptedPreparationGuardTest {
    private val target = Instant.parse("2026-10-08T21:00:00Z")
    private val date = LocalDate.of(2026, 10, 9)
    private val plan = OrderPlan(date, 1, LocalTime.of(6, 0), DatePolicy.MANUAL, ReservationReason.NONE)
    private val settings = AppSettings(masterEnabled = true, liveScope = LiveScope.RECURRING,
        displayPriceRiskAccepted = true, generation = 4L, accountGeneration = 2L)
    private fun eligible(current: AppSettings = settings, now: Instant = target.minusSeconds(60),
        plans: List<OrderPlan> = listOf(plan), records: List<ExecutionRecord> = emptyList(), foreground: Boolean = true,
        generation: Long = 4L, account: Long = 2L, consumedTarget: Long = target.toEpochMilli(), consumedGeneration: Long = 4L) =
        InterruptedPreparationGuard.eligible(current, plans, records, now, target.toEpochMilli(), generation, account,
            consumedTarget, consumedGeneration, foreground)

    @Test fun sameConsumedFutureAttemptCanResumeOnlyFromForeground() {
        assertEquals(plan, eligible())
        assertNull(eligible(foreground = false))
        assertNull(eligible(consumedTarget = 0L))
        assertNull(eligible(consumedGeneration = 3L))
    }

    @Test fun targetIsFutureWithinBoundedWaitAndNeverCaughtUp() {
        assertEquals(plan, eligible(now = target.minusSeconds(180)))
        assertNull(eligible(now = target.minusSeconds(181)))
        assertNull(eligible(now = target))
        assertNull(eligible(now = target.plusSeconds(1)))
    }

    @Test fun settingsAccountScopeAndEffectivePlanMustStillMatch() {
        assertNull(eligible(current = settings.copy(masterEnabled = false)))
        assertNull(eligible(current = settings.copy(displayPriceRiskAccepted = false)))
        assertNull(eligible(current = settings.copy(liveBlockedReason = "blocked")))
        assertNull(eligible(current = settings.copy(liveScope = LiveScope.NONE)))
        assertNull(eligible(current = settings.copy(liveScope = LiveScope.SINGLE_DATE, liveTestDate = date.plusDays(1))))
        assertNull(eligible(generation = 3L))
        assertNull(eligible(account = 1L))
        assertNull(eligible(account = -1L))
        assertNull(eligible(plans = emptyList()))
        assertNull(eligible(plans = listOf(plan.copy(time = LocalTime.of(6, 1)))))
    }

    @Test fun protectedLedgerBlocksResumeButPureLookupDoesNot() {
        val observation = ExecutionRecord(date, 1, ExecutionStatus.NEEDS_CHECK, "HISTORY_EMPTY", "lookup")
        assertEquals(plan, eligible(records = listOf(observation)))
        listOf(observation.copy(stage = "SUBMIT"), observation.copy(status = ExecutionStatus.COMPLETED),
            observation.copy(status = ExecutionStatus.FAILED, submissionPossible = true),
            observation.copy(serverOrderId = "protected")).forEach {
            assertNull(eligible(records = listOf(it)))
        }
    }
}
