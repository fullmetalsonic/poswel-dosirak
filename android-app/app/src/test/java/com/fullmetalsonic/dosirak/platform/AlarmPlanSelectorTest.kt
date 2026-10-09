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

    @Test fun legacyPureLookupDoesNotPreventFutureOrExistingDueAlarm() {
        listOf("HISTORY", "HISTORY_EMPTY").forEach { stage ->
            val future = plan(date.plusDays(6))
            val record = ExecutionRecord(future.date, 1, ExecutionStatus.NEEDS_CHECK, stage, "lookup")
            assertEquals(future, AlarmPlanSelector.next(settings, listOf(future), listOf(record), now))
            assertEquals(plan(), AlarmPlanSelector.existingDue(settings, listOf(plan()), listOf(record.copy(date = date)),
                now.plusSeconds(1), now.toEpochMilli(), settings.generation, true))
        }
    }

    @Test fun conflictAndLookupWithServerOrTransmissionEvidenceStillBlockAlarms() {
        val future = plan(date.plusDays(6))
        val lookup = ExecutionRecord(future.date, 1, ExecutionStatus.NEEDS_CHECK, "HISTORY", "lookup")
        listOf(lookup.copy(stage = "HISTORY_CONFLICT"), lookup.copy(amount = 10000),
            lookup.copy(serverOrderId = "order"), lookup.copy(submissionPossible = true)).forEach { record ->
            assertNull(AlarmPlanSelector.next(settings, listOf(future), listOf(record), now))
            assertNull(AlarmPlanSelector.existingDue(settings, listOf(plan()), listOf(record.copy(date = date)),
                now.plusSeconds(1), now.toEpochMilli(), settings.generation, true))
        }
    }

    @Test fun nextAlarmIsChronologicalAndPreparationIndependent() {
        val next = plan(date.plusDays(1))
        assertEquals(next, AlarmPlanSelector.next(settings.copy(preparationAlert = false), listOf(plan(date.plusDays(3)), next), emptyList(), now))
        assertEquals(next, AlarmPlanSelector.next(settings.copy(preparationAlert = true), listOf(next), emptyList(), now))
    }

    @Test fun outOfWindowEarlierPlanIsSkippedForLaterValidPlan() {
        val next = plan(date.plusDays(2))
        val beforeOpening = plan(date.plusDays(1), LocalTime.of(5, 59, 59))
        val afterClosing = plan(date.plusDays(1), LocalTime.of(8, 0))
        assertEquals(next, AlarmPlanSelector.next(settings, listOf(beforeOpening, next), emptyList(), now))
        assertEquals(next, AlarmPlanSelector.next(settings, listOf(afterClosing, next), emptyList(), now))
    }

    @Test fun orderWindowIncludesSixButExcludesEight() {
        val opening = plan(date.plusDays(1), LocalTime.of(6, 0))
        val lastSecond = plan(date.plusDays(1), LocalTime.of(7, 59, 59))
        val closing = plan(date.plusDays(1), LocalTime.of(8, 0))
        assertEquals(opening, AlarmPlanSelector.next(settings, listOf(opening), emptyList(), now))
        assertEquals(lastSecond, AlarmPlanSelector.next(settings, listOf(lastSecond), emptyList(), now))
        assertNull(AlarmPlanSelector.next(settings, listOf(closing), emptyList(), now))
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

    private fun earlyWake(config: AppSettings = settings, plans: List<OrderPlan> = listOf(plan()),
        records: List<ExecutionRecord> = emptyList(), logicalNow: Instant = now.minusSeconds(90),
        wallNow: Long = now.minusSeconds(90).toEpochMilli(), generation: Long = settings.generation,
        accountGeneration: Long = settings.accountGeneration, hasToken: Boolean = true,
        consumedTarget: Long = 0L, consumedGeneration: Long = -1L): OrderPlan? =
        AlarmPlanSelector.existingEarlyWake(config, plans, records, logicalNow, now.toEpochMilli(), generation,
            accountGeneration, hasToken, now.minusSeconds(120).toEpochMilli(), now.toEpochMilli(), wallNow,
            consumedTarget, consumedGeneration)

    @Test fun deliveredEarlyWakePreservesExactPlanUntilBothClocksReachTarget() {
        assertEquals(plan(), earlyWake())
        assertEquals(plan(), earlyWake(logicalNow = now.minusSeconds(120), wallNow = now.minusSeconds(120).toEpochMilli()))
        assertNull(earlyWake(logicalNow = now))
        assertNull(earlyWake(wallNow = now.toEpochMilli()))
        assertNull(earlyWake(wallNow = now.minusSeconds(121).toEpochMilli()))
    }

    @Test fun earlyWakeRequiresOriginalUnconsumedTokenSettingsAndAccountGeneration() {
        assertNull(earlyWake(hasToken = false))
        assertNull(earlyWake(generation = settings.generation + 1))
        assertNull(earlyWake(accountGeneration = settings.accountGeneration + 1))
        assertNull(earlyWake(consumedTarget = now.toEpochMilli(), consumedGeneration = settings.generation))
        assertEquals(plan(), earlyWake(consumedTarget = now.toEpochMilli(), consumedGeneration = settings.generation - 1))
        assertNull(earlyWake(plans = listOf(plan(time = LocalTime.of(6, 1)))))
        assertNull(earlyWake(plans = emptyList()))
    }

    @Test fun earlyWakeDoesNotBypassAuthorizationOrSubmissionProtection() {
        listOf(settings.copy(masterEnabled = false), settings.copy(liveScope = LiveScope.NONE),
            settings.copy(displayPriceRiskAccepted = false), settings.copy(liveBlockedReason = "blocked"),
            settings.copy(liveScope = LiveScope.SINGLE_DATE, liveTestDate = date.plusDays(1))).forEach {
            assertNull(earlyWake(config = it))
        }
        listOf(ExecutionRecord(date, 1, ExecutionStatus.COMPLETED, "DONE", "done"),
            ExecutionRecord(date, 1, ExecutionStatus.NEEDS_CHECK, "SUBMIT", "unknown"),
            ExecutionRecord(date, 1, ExecutionStatus.FAILED, "NETWORK", "unknown", submissionPossible = true)).forEach {
            assertNull(earlyWake(records = listOf(it)))
        }
    }

    @Test fun consumedPreparationFailureSkipsTodayAndSelectsNextDayBeforeTarget() {
        val future = plan(date.plusDays(1))
        val failed = ExecutionRecord(date, 1, ExecutionStatus.FAILED, "NETWORK", "preparation failed")
        assertEquals(future, AlarmPlanSelector.next(settings.copy(backgroundCheckEnabled = false), listOf(plan(), future),
            listOf(failed), now.minusSeconds(90), now.toEpochMilli(), settings.generation))
        assertEquals(plan(), AlarmPlanSelector.next(settings, listOf(plan(), future), listOf(failed),
            now.minusSeconds(90), now.toEpochMilli(), settings.generation - 1))
        assertNull(AlarmPlanSelector.next(settings.copy(liveScope = LiveScope.SINGLE_DATE, liveTestDate = date),
            listOf(plan(), future), listOf(failed), now.minusSeconds(90), now.toEpochMilli(), settings.generation))
    }

    @Test fun consumedFilteringKeepsProtectedNextDayRecords() {
        val protectedDate = date.plusDays(1)
        val next = plan(date.plusDays(2))
        val protected = ExecutionRecord(protectedDate, 1, ExecutionStatus.NEEDS_CHECK, "SUBMIT", "unknown", submissionPossible = true)
        assertEquals(next, AlarmPlanSelector.next(settings, listOf(plan(), plan(protectedDate), next), listOf(protected),
            now.minusSeconds(90), now.toEpochMilli(), settings.generation))
    }
}
