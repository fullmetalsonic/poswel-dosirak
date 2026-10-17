package com.fullmetalsonic.dosirak.domain

import com.fullmetalsonic.dosirak.site.SiteOrder
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class RebookingTest {
    private val day = LocalDate.of(2026, 10, 17)
    private val now = Instant.parse("2026-10-16T21:14:10Z")
    private val settings = AppSettings(accountGeneration = 4, generation = 8)
    private val complete = SiteOrder("new-order", day, 1, "주문완료", 5000, "점심")
    private fun cancelled(at: String = "2026-10-17T06:14", menu: String = "점심") =
        SiteOrder(null, day, null, "취소", null, menu, at)
    private fun baseline(previous: List<SiteOrder> = emptyList()) =
        requireNotNull(Rebooking.baseline(previous + complete, day, "account", settings, now.minusSeconds(10)))

    @Test fun onlyVerifiedOrExplicitLegacyHistoryConflictLedgerCanArm() {
        val record = baseline().baseline
        assertTrue(Rebooking.ledgerAllowsArm(record, day, 4))
        assertTrue(Rebooking.ledgerAllowsArm(record.copy(status = ExecutionStatus.NEEDS_CHECK, stage = "HISTORY_CONFLICT"), day, 4))
        assertFalse(Rebooking.ledgerAllowsArm(null, day, 4))
        assertFalse(Rebooking.ledgerAllowsArm(record, day.plusDays(1), 4))
        assertFalse(Rebooking.ledgerAllowsArm(record, day, 5))
        listOf("TEMP_UNKNOWN", "SUBMIT_UNKNOWN", "TEMP_INTENT", "SUBMIT_INTENT", "GUARD", "REBOOK_ARMED")
            .forEach { stage -> assertFalse(Rebooking.ledgerAllowsArm(record.copy(stage = stage), day, 4)) }
        assertFalse(Rebooking.ledgerAllowsArm(record.copy(status = ExecutionStatus.NEEDS_CHECK), day, 4))
    }

    @Test fun unknownOrConsumedAttemptNeedsMatchingQuantityAndKnownAmountBeforeBaselineReplacement() {
        val old = baseline().copy(permit = RebookingPermit("used", 2, LocalTime.of(6, 16), 9, true))
        val unknown = old.baseline.copy(quantity = 2, amount = 10000, stage = "SUBMIT_UNKNOWN", status = ExecutionStatus.NEEDS_CHECK)
        val candidate = baseline().copy(baseline = baseline().baseline.copy(serverOrderId = "new-id"))
        assertFalse(Rebooking.baselineMayReplace(old, unknown, candidate))
        assertFalse(Rebooking.baselineMayReplace(null, unknown, candidate))
        assertFalse(Rebooking.baselineMayReplace(null, unknown.copy(submissionPossible = false), candidate))
        val correct = candidate.copy(baseline = candidate.baseline.copy(quantity = 2, amount = 10000))
        assertTrue(Rebooking.baselineMayReplace(old, unknown, correct))
        assertTrue(Rebooking.baselineMayReplace(null, unknown, correct))
        assertFalse(Rebooking.baselineMayReplace(old, unknown.copy(amount = 12000), correct))
        assertTrue(Rebooking.baselineMayReplace(null, unknown.copy(stage = "HISTORY_CONFLICT"), candidate))
    }

    @Test fun onlyFreshSingleNativeCompletedRowCanCreateBaseline() {
        assertNotNull(Rebooking.baseline(listOf(complete), day, "account", settings, now))
        listOf(emptyList(), listOf(complete, complete), listOf(complete.copy(id = null)),
            listOf(complete.copy(quantity = null)), listOf(complete.copy(total = 0)),
            listOf(complete.copy(status = "진행중")), listOf(complete, cancelled().copy(cancelledAt = null)))
            .forEach { assertNull(Rebooking.baseline(it, day, "account", settings, now)) }
    }

    @Test fun exactCancellationIncrementProducesProofWithoutDeletingOriginal() {
        val old = cancelled("2026-10-17T06:10")
        val state = baseline(listOf(old, old))
        val proven = requireNotNull(Rebooking.proof(state, listOf(old, cancelled(), old), now))
        assertEquals(3, proven.proof?.size)
        assertEquals("new-order", proven.baseline.serverOrderId)
        assertEquals(2, proven.cancelled.size)
    }

    @Test fun emptyUnknownActiveMissingAndAmbiguousHistoryNeverProvesCancellation() {
        val old = cancelled("2026-10-17T06:10")
        val state = baseline(listOf(old))
        listOf(emptyList(), listOf(old), listOf(cancelled()), listOf(old, cancelled(), cancelled()),
            listOf(old, complete), listOf(old, cancelled().copy(status = "취소요청")),
            listOf(old, cancelled().copy(id = complete.id)), listOf(old, cancelled().copy(cancelledAt = null)),
            listOf(old, cancelled("2026-10-17T06:00")), listOf(old, cancelled(menu = "다른 메뉴")))
            .forEach { assertNull(Rebooking.proof(state, it, now)) }
    }

    @Test fun permitIsBoundToAccountDatePlanGenerationAndConsumedOnlyOnce() {
        val state = requireNotNull(Rebooking.proof(baseline(), listOf(cancelled()), now))
            .copy(permit = RebookingPermit("private-attempt", 1, LocalTime.of(6, 16), 9))
        val current = settings.copy(generation = 9)
        val plan = OrderPlan(day, 1, LocalTime.of(6, 16), DatePolicy.MANUAL, ReservationReason.NONE)
        assertTrue(Rebooking.permitMatches(state, current, plan, "account"))
        assertFalse(Rebooking.permitMatches(state.copy(permit = state.permit!!.copy(consumed = true)), current, plan, "account"))
        assertFalse(Rebooking.permitMatches(state, current.copy(generation = 10), plan, "account"))
        assertFalse(Rebooking.permitMatches(state, current.copy(accountGeneration = 5), plan, "account"))
        assertFalse(Rebooking.permitMatches(state, current, plan.copy(quantity = 2), "account"))
        assertFalse(Rebooking.permitMatches(state, current, plan.copy(time = LocalTime.of(6, 17)), "account"))
        assertFalse(Rebooking.permitMatches(state, current, plan.copy(date = day.plusDays(1)), "account"))
        assertFalse(Rebooking.permitMatches(state, current, plan, "other"))
        assertFalse(Rebooking.permitMatches(state.copy(proof = null), current, plan, "account"))
    }
}
