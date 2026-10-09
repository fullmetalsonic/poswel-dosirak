package com.fullmetalsonic.dosirak.ui

import com.fullmetalsonic.dosirak.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReservationPresentationTest {
    private val date = LocalDate.of(2026, 10, 17)
    private val settings = AppSettings()
    private val manual = DateOverride(date, DatePolicy.MANUAL, 2, reason = ReservationReason.SUPPORT)
    private val observation = ExecutionRecord(date, 0, ExecutionStatus.NEEDS_CHECK, "HISTORY", "query")

    @Test fun legacyObservationDoesNotHideManualReservation() {
        listOf("HISTORY", "HISTORY_EMPTY").forEach { stage ->
            val shown = reservationPresentation(date, settings, manual, listOf(observation.copy(stage = stage)))
            assertEquals("예약 2개", shown.reservationLabel)
            assertNull(shown.orderLabel)
            assertFalse(shown.siteOrderMayExist)
        }
    }
    @Test fun legacyObservationDoesNotHideCancellation() {
        val shown = reservationPresentation(date, settings, DateOverride(date, DatePolicy.EXCLUDE), listOf(observation))
        assertEquals("예약 취소", shown.reservationLabel)
        assertNull(shown.orderLabel)
    }
    @Test fun trueUncertainRecordIsSeparateFromReservation() {
        val shown = reservationPresentation(date, settings, manual,
            listOf(observation.copy(stage = "SUBMISSION_UNKNOWN", submissionPossible = true), observation))
        assertEquals("예약 2개", shown.reservationLabel)
        assertEquals("주문 확인 필요", shown.orderLabel)
        assertTrue(shown.siteOrderMayExist)
    }
    @Test fun completedRecordSurvivesLaterQueryAndCancelledPlan() {
        val shown = reservationPresentation(date, settings, DateOverride(date, DatePolicy.EXCLUDE),
            listOf(observation.copy(status = ExecutionStatus.COMPLETED, quantity = 2, stage = "COMPLETE", serverOrderId = "test"), observation))
        assertEquals("예약 취소", shown.reservationLabel)
        assertEquals("주문완료 2개", shown.orderLabel)
        assertTrue(shown.siteOrderMayExist)
    }
    @Test fun closeRequiresChangedGenerationAndMatchingStoredOverride() {
        assertFalse(reservationSaved(4, 4, manual, manual))
        assertFalse(reservationSaved(5, 4, null, manual))
        assertTrue(reservationSaved(5, 4, manual, manual))
        assertTrue(reservationSaved(5, 4, null, null))
        assertFalse(reservationSaved(5, 4, manual, null))
    }
}
