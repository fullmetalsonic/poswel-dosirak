package com.fullmetalsonic.dosirak.ui

import com.fullmetalsonic.dosirak.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class RebookInputTest {
    private val now = ZonedDateTime.parse("2026-10-17T06:30:00+09:00[Asia/Seoul]")
    private val today = now.toLocalDate()
    private val protected = ExecutionRecord(today, 1, ExecutionStatus.COMPLETED, "VERIFIED", "test", accountGeneration = 4)
    private val state = UiState(settings = AppSettings(accountGeneration = 4), records = listOf(protected))

    @Test fun onlyTodaysProtectedCurrentAccountRecordOffersRebook() {
        assertTrue(canOfferRebook(today, today, state))
        assertFalse(canOfferRebook(today.minusDays(1), today, state.copy(records = listOf(protected.copy(date = today.minusDays(1))))))
        assertFalse(canOfferRebook(today, today, state.copy(settings = state.settings.copy(accountGeneration = 5))))
        assertFalse(canOfferRebook(today, today, state.copy(records = listOf(protected.copy(status = ExecutionStatus.PLANNED)))))
        assertTrue(canOfferRebook(today, today, state.copy(records = listOf(protected.copy(status = ExecutionStatus.FAILED, submissionPossible = true)))))
    }

    @Test fun quantityAndStrictSecondPrecisionAreValidated() {
        assertNull(rebookInputError(today, "2", "06:32:00", now))
        listOf("0", "6", "", "1.0").forEach { assertNotNull(rebookInputError(today, it, "06:32:00", now)) }
        listOf("6:32:00", "06:32", "06:32:60", "08:00:00").forEach { assertNotNull(rebookInputError(today, "1", it, now)) }
    }

    @Test fun pastEqualFutureDatesAndWindowAreRejected() {
        assertNotNull(rebookInputError(today, "1", "06:30:00", now))
        assertNotNull(rebookInputError(today, "1", "06:29:59", now))
        assertNotNull(rebookInputError(today.plusDays(1), "1", "06:32:00", now))
        assertNotNull(rebookInputError(LocalDate.of(2026, 10, 16), "1", "06:32:00", now))
        assertNull(rebookInputError(today, "5", "07:59:59", now))
        assertNotNull(rebookInputError(today, "1", "05:59:59", now.withHour(5)))
    }
}
