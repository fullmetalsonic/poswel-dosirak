package com.fullmetalsonic.dosirak.platform

import com.fullmetalsonic.dosirak.domain.AppSettings
import com.fullmetalsonic.dosirak.domain.ExecutionRecord
import com.fullmetalsonic.dosirak.domain.ExecutionStatus
import com.fullmetalsonic.dosirak.domain.LiveScope
import com.fullmetalsonic.dosirak.domain.OrderPlan
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

internal object AlarmPlanSelector {
    fun next(settings: AppSettings, plans: List<OrderPlan>, records: List<ExecutionRecord>, now: Instant): OrderPlan? {
        if (!authorized(settings)) return null
        val zone = ZoneId.of("Asia/Seoul")
        return plans.asSequence()
            .filter { eligible(settings, it, records) }
            .filter { it.date.atTime(it.time).atZone(zone).toInstant().isAfter(now) }
            .minByOrNull { it.date.atTime(it.time).atZone(zone).toInstant() }
    }

    fun existingDue(settings: AppSettings, plans: List<OrderPlan>, records: List<ExecutionRecord>, now: Instant,
        epoch: Long, generation: Long, hasToken: Boolean): OrderPlan? {
        if (!hasToken || epoch <= 0L || generation != settings.generation || !authorized(settings)) return null
        val zone = ZoneId.of("Asia/Seoul")
        val current = now.atZone(zone)
        val scheduled = Instant.ofEpochMilli(epoch).atZone(zone)
        if (scheduled.toLocalDate() != current.toLocalDate() || epoch > now.toEpochMilli() ||
            scheduled.toLocalTime() < LocalTime.of(6, 0) || scheduled.toLocalTime() >= LocalTime.of(8, 0) ||
            current.toLocalTime() < LocalTime.of(6, 0) || current.toLocalTime() >= LocalTime.of(8, 0)) return null
        return plans.firstOrNull { it.date == scheduled.toLocalDate() && it.time == scheduled.toLocalTime() && eligible(settings, it, records) }
    }

    private fun authorized(settings: AppSettings) = settings.masterEnabled && settings.liveScope != LiveScope.NONE &&
        settings.displayPriceRiskAccepted && settings.liveBlockedReason == null

    private fun eligible(settings: AppSettings, plan: OrderPlan, records: List<ExecutionRecord>) =
        (settings.liveScope != LiveScope.SINGLE_DATE || plan.date == settings.liveTestDate) &&
            records.none { it.date == plan.date && (it.status == ExecutionStatus.COMPLETED || it.status == ExecutionStatus.NEEDS_CHECK || it.submissionPossible) }
}
