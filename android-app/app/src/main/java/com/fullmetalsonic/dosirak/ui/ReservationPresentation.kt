package com.fullmetalsonic.dosirak.ui

import com.fullmetalsonic.dosirak.domain.*
import java.time.LocalDate

internal data class ReservationPresentation(val plan: OrderPlan?, val reservationLabel: String,
    val orderLabel: String?, val siteOrderMayExist: Boolean)

internal fun reservationPresentation(date: LocalDate, settings: AppSettings,
    override: DateOverride?, records: List<ExecutionRecord>): ReservationPresentation {
    val plan = ScheduleCalculator.planFor(date, settings, override)
    val real = records.filter { it.date == date && !it.isNonSubmissionObservation() }
    val latest = real.maxByOrNull { it.updatedAt }
    val completed = real.filter { it.status == ExecutionStatus.COMPLETED }.maxByOrNull { it.updatedAt }
    val uncertain = real.any { it.status == ExecutionStatus.NEEDS_CHECK || it.status == ExecutionStatus.RUNNING ||
        it.submissionPossible && it.status != ExecutionStatus.COMPLETED }
    val siteOrderMayExist = real.any { it.submissionPossible || it.status == ExecutionStatus.COMPLETED ||
        it.status == ExecutionStatus.NEEDS_CHECK || it.status == ExecutionStatus.RUNNING }
    val orderLabel = when {
        uncertain -> "주문 확인 필요"
        completed != null -> "주문완료 ${completed.quantity}개"
        latest?.status == ExecutionStatus.FAILED -> "주문 실패"
        else -> null
    }
    return ReservationPresentation(plan,
        plan?.let { "예약 ${it.quantity}개" } ?: if (override?.policy == DatePolicy.EXCLUDE) "예약 취소" else "",
        orderLabel, siteOrderMayExist)
}

internal fun reservationSaved(currentGeneration: Long, beforeGeneration: Long,
    actual: DateOverride?, expected: DateOverride?): Boolean =
    currentGeneration != beforeGeneration && actual == expected
