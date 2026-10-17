package com.fullmetalsonic.dosirak.domain

import com.fullmetalsonic.dosirak.site.SiteOrder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Private CE state. Never include account, history keys or permit IDs in exports/diagnostics. */
data class RebookingState(
    val date: LocalDate,
    val account: String,
    val accountGeneration: Long,
    val baseline: ExecutionRecord,
    val menu: String,
    val cancelled: List<String>,
    val observedAt: Instant,
    val proof: List<String>? = null,
    val proofAt: Instant? = null,
    val permit: RebookingPermit? = null
)

data class RebookingPermit(
    val id: String,
    val quantity: Int,
    val time: LocalTime,
    val generation: Long,
    val consumed: Boolean = false
)

object Rebooking {
    val cancelledStatuses = setOf("주문취소", "취소완료", "취소")
    fun ledgerAllowsArm(record: ExecutionRecord?, date: LocalDate, accountGeneration: Long): Boolean =
        record != null && record.date == date && record.accountGeneration == accountGeneration &&
            ((record.status == ExecutionStatus.COMPLETED && record.stage == "VERIFIED") ||
                (record.status == ExecutionStatus.NEEDS_CHECK && record.stage == "HISTORY_CONFLICT"))

    fun unresolvedLedger(record: ExecutionRecord?): Boolean = record != null &&
        (record.stage in setOf("TEMP_UNKNOWN", "SUBMIT_UNKNOWN", "TEMP_INTENT", "SUBMIT_INTENT") ||
            (record.submissionPossible && !ledgerAllowsArm(record, record.date, record.accountGeneration)))

    fun baselineMayReplace(previous: RebookingState?, ledger: ExecutionRecord?, candidate: RebookingState): Boolean {
        if (previous?.permit == null && !unresolvedLedger(ledger)) return true
        if (ledger == null || ledger.date != candidate.date || ledger.accountGeneration != candidate.accountGeneration) return false
        val quantity = previous?.permit?.quantity ?: ledger.quantity
        return candidate.baseline.quantity == quantity && candidate.baseline.quantity == ledger.quantity &&
            (ledger.amount == null || ledger.amount == candidate.baseline.amount)
    }

    private fun key(order: SiteOrder): String? {
        if (order.status.trim() !in cancelledStatuses || order.cancelledAt == null) return null
        return listOf(order.status.trim(), order.menu, order.cancelledAt).joinToString("\u001f")
    }

    fun baseline(orders: List<SiteOrder>, date: LocalDate, account: String,
        settings: AppSettings, now: Instant): RebookingState? {
        val day = orders.filter { it.date == date }
        val active = day.filter { it.status.trim() !in cancelledStatuses }
        val one = active.singleOrNull() ?: return null
        if (one.status.trim() != "주문완료" || one.id.isNullOrBlank() || one.quantity == null || one.quantity !in 1..5 || (one.total ?: 0) <= 0) return null
        val cancelled = day.filter { it !== one }.map { key(it) ?: return null }.sorted()
        val record = ExecutionRecord(date, requireNotNull(one.quantity), ExecutionStatus.COMPLETED, "VERIFIED",
            "사이트 주문완료를 확인했습니다.", now, one.total, one.id, true, settings.accountGeneration, settings.generation)
        return RebookingState(date, account, settings.accountGeneration, record, one.menu, cancelled, now)
    }

    fun proof(state: RebookingState, orders: List<SiteOrder>, now: Instant): RebookingState? {
        val day = orders.filter { it.date == state.date }
        if (day.any { it.id == state.baseline.serverOrderId || it.status.trim() !in cancelledStatuses }) return null
        val keys = day.map { key(it) ?: return null }.sorted()
        val remaining = keys.toMutableList()
        state.cancelled.forEach { if (!remaining.remove(it)) return null }
        if (remaining.size != 1) return null
        val added = day.firstOrNull { key(it) == remaining.single() } ?: return null
        val cancelledAt = runCatching { LocalDateTime.parse(added.cancelledAt).atZone(ZoneId.of("Asia/Seoul")).toInstant() }.getOrNull() ?: return null
        if (added.menu != state.menu || cancelledAt < state.observedAt.minusSeconds(60) || cancelledAt > now.plusSeconds(60)) return null
        return state.copy(proof = keys, proofAt = now)
    }

    fun permitMatches(state: RebookingState, settings: AppSettings, plan: OrderPlan?, account: String?): Boolean {
        val permit = state.permit ?: return false
        return !permit.consumed && state.account == account && state.accountGeneration == settings.accountGeneration &&
            permit.generation == settings.generation && plan != null && plan.date == state.date &&
            permit.quantity == plan.quantity && permit.time == plan.time && state.proof != null
    }
}
