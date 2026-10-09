package com.fullmetalsonic.dosirak.domain

/** Legacy read-only lookup results are not evidence that an order was submitted. */
fun ExecutionRecord.isNonSubmissionObservation(): Boolean =
    status == ExecutionStatus.NEEDS_CHECK && stage in setOf("HISTORY", "HISTORY_EMPTY") &&
        !submissionPossible && serverOrderId == null && amount == null
