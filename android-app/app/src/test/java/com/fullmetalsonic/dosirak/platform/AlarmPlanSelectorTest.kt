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

class AlarmPlanSelectorTest {
    private val date = LocalDate.of(2026, 10, 9)
    private val now = Instant.parse("2026-10-08T21:00:00Z")
    private val settings = AppSettings(masterEnabled = true, liveScope = LiveScope.RECURRING, displayPriceRiskAccepted = true)
    private fun plan(day: LocalDate = date, time: LocalTime = LocalTime.of(6, 0)) = OrderPlan(day, 1, time, DatePolicy.MANUAL, ReservationReason.NONE)

    @Test fun masterAndAuthorizationRemainIndependent() {
        val plans = listOf(plan(date.plusDays(1)))
        assertNull(AlarmPlanSelector.next(settings.copy(masterEnabled = false), plans, emptyList(), now))
        assertNull(AlarmPlanSelector.next(settings.copy(liveScope = LiveScope.NONE), plans, emptyList(), now))
        assertNull(AlarmPlanSelector.next(settings.copy(displayPriceRiskAccepted = false), plans, emptyList(), now))
    }

    @Test fun blockedReasonPreservesMasterIntentButDoesNotScheduleAlarm() {
        val blocked = settings.copy(liveBlockedReason = "사이트 계약 확인 필요")
        assertNull(AlarmPlanSelector.next(blocked, listOf(plan(date.plusDays(1))), emptyList(), now))
        org.junit.Assert.assertTrue(blocked.masterEnabled)
        assertEquals(LiveScope.RECURRING, blocked.liveScope)
    }

    @Test fun pastAndDuePlansAreNeverCaughtUp() {
        val next = plan(date.plusDays(1))
        assertEquals(next, AlarmPlanSelector.next(settings, listOf(plan(date.minusDays(1)), plan(), next), emptyList(), now))
        assertNull(AlarmPlanSelector.next(settings, listOf(plan()), emptyList(), now))
    }

    @Test fun singleDateCannotScheduleAnyOtherDate() {
        val selected = plan(date.plusDays(2))
        assertEquals(selected, AlarmPlanSelector.next(settings.copy(liveScope = LiveScope.SINGLE_DATE, liveTestDate = selected.date),
            listOf(plan(date.plusDays(1)), selected), emptyList(), now))
        assertNull(AlarmPlanSelector.next(settings.copy(liveScope = LiveScope.SINGLE_DATE, liveTestDate = null), listOf(selected), emptyList(), now))
    }

    @Test fun completionAndUncertainTransmissionBlockNewAlarm() {
        val next = plan(date.plusDays(1))
        listOf(ExecutionStatus.COMPLETED, ExecutionStatus.NEEDS_CHECK).forEach { status ->
            val record = ExecutionRecord(next.date, 1, status, "test", "test")
            assertNull(AlarmPlanSelector.next(settings, listOf(next), listOf(record), now))
        }
        val sent = ExecutionRecord(next.date, 1, ExecutionStatus.FAILED, "test", "test", submissionPossible = true)
        assertNull(AlarmPlanSelector.next(settings, listOf(next), listOf(sent), now))
    }

    @Test fun nextAlarmIsChronologicalAndPreparationIndependent() {
        val next = plan(date.plusDays(1))
        assertEquals(next, AlarmPlanSelector.next(settings.copy(preparationAlert = false), listOf(plan(date.plusDays(3)), next), emptyList(), now))
        assertEquals(next, AlarmPlanSelector.next(settings.copy(preparationAlert = true), listOf(next), emptyList(), now))
    }

    @Test fun registeredUnconsumedDueAlarmSurvivesOrdinaryRefresh() {
        val due = plan()
        assertEquals(due, AlarmPlanSelector.existingDue(settings, listOf(due), emptyList(), now.plusSeconds(1), now.toEpochMilli(), settings.generation, true))
        assertEquals(due, AlarmPlanSelector.existingDue(settings, listOf(due), emptyList(), now.plusSeconds(7_199), now.toEpochMilli(), settings.generation, true))
        assertNull(AlarmPlanSelector.existingDue(settings, listOf(due), emptyList(), now.plusSeconds(7_200), now.toEpochMilli(), settings.generation, true))
        assertNull(AlarmPlanSelector.existingDue(settings, listOf(plan(time = LocalTime.of(5, 59))), emptyList(), now.plusSeconds(1), now.minusSeconds(60).toEpochMilli(), settings.generation, true))
    }

    @Test fun stoppedExcludedOrChangedDueAlarmIsNotPreserved() {
        val due = plan()
        val after = now.plusSeconds(1)
        val epoch = now.toEpochMilli()
        assertNull(AlarmPlanSelector.existingDue(settings.copy(masterEnabled = false), listOf(due), emptyList(), after, epoch, settings.generation, true))
        assertNull(AlarmPlanSelector.existingDue(settings, emptyList(), emptyList(), after, epoch, settings.generation, true))
        assertNull(AlarmPlanSelector.existingDue(settings.copy(generation = settings.generation + 1), listOf(due), emptyList(), after, epoch, settings.generation, true))
        assertNull(AlarmPlanSelector.existingDue(settings, listOf(due.copy(time = LocalTime.of(6, 1))), emptyList(), after, epoch, settings.generation, true))
    }

    @Test fun uncertainAndBlockedDueAlarmIsNotPreserved() {
        val due = plan()
        val record = ExecutionRecord(date, 1, ExecutionStatus.NEEDS_CHECK, "test", "test")
        assertNull(AlarmPlanSelector.existingDue(settings, listOf(due), listOf(record), now.plusSeconds(1), now.toEpochMilli(), settings.generation, true))
        assertNull(AlarmPlanSelector.existingDue(settings, listOf(due), listOf(record.copy(status = ExecutionStatus.COMPLETED)), now.plusSeconds(1), now.toEpochMilli(), settings.generation, true))
        assertNull(AlarmPlanSelector.existingDue(settings, listOf(due), listOf(record.copy(status = ExecutionStatus.FAILED, submissionPossible = true)), now.plusSeconds(1), now.toEpochMilli(), settings.generation, true))
        assertNull(AlarmPlanSelector.existingDue(settings.copy(liveBlockedReason = "차단"), listOf(due), emptyList(), now.plusSeconds(1), now.toEpochMilli(), settings.generation, true))
    }

    @Test fun newlyEnabledPastPlanAndConsumedAlarmAreNotCaughtUp() {
        assertNull(AlarmPlanSelector.existingDue(settings, listOf(plan()), emptyList(), now.plusSeconds(1), 0, settings.generation, false))
        assertNull(AlarmPlanSelector.existingDue(settings, listOf(plan()), emptyList(), now.plusSeconds(1), now.toEpochMilli(), settings.generation, false))
        assertNull(AlarmPlanSelector.next(settings, listOf(plan()), emptyList(), now.plusSeconds(1)))
    }
}
