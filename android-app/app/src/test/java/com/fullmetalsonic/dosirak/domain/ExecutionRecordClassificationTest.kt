package com.fullmetalsonic.dosirak.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ExecutionRecordClassificationTest {
    private val observation = ExecutionRecord(LocalDate.of(2026, 10, 15), 1,
        ExecutionStatus.NEEDS_CHECK, "HISTORY", "lookup")

    @Test fun onlyExactLegacyLookupStatesAreNonSubmissionObservations() {
        assertTrue(observation.isNonSubmissionObservation())
        assertTrue(observation.copy(stage = "HISTORY_EMPTY").isNonSubmissionObservation())
        listOf("HISTORY_CONFLICT", "SUBMIT_INTENT", "TEMP_UNKNOWN", "ACCOUNT_CHANGED", "LOOKUP_ERROR", "history")
            .forEach { assertFalse(observation.copy(stage = it).isNonSubmissionObservation()) }
        ExecutionStatus.values().filter { it != ExecutionStatus.NEEDS_CHECK }
            .forEach { assertFalse(observation.copy(status = it).isNonSubmissionObservation()) }
    }

    @Test fun anyTransmissionOrServerEvidenceKeepsLegacyLookupProtected() {
        assertFalse(observation.copy(submissionPossible = true).isNonSubmissionObservation())
        listOf(0L, 10000L).forEach { assertFalse(observation.copy(amount = it).isNonSubmissionObservation()) }
        listOf("", "order").forEach { assertFalse(observation.copy(serverOrderId = it).isNonSubmissionObservation()) }
    }
}
