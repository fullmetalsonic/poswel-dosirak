package com.fullmetalsonic.dosirak.platform

import com.fullmetalsonic.dosirak.domain.AppSettings
import com.fullmetalsonic.dosirak.domain.ExecutionRecord
import com.fullmetalsonic.dosirak.domain.OrderPlan
import java.time.Instant
import java.time.ZoneId

internal object InterruptedPreparationGuard {
    private val zone = ZoneId.of("Asia/Seoul")

    fun eligible(settings: AppSettings, plans: List<OrderPlan>, records: List<ExecutionRecord>, now: Instant,
        target: Long, generation: Long, accountGeneration: Long, consumedTarget: Long, consumedGeneration: Long,
        foregroundConfirmed: Boolean): OrderPlan? {
        if (!foregroundConfirmed || generation != settings.generation || accountGeneration < 0L ||
            accountGeneration != settings.accountGeneration || target <= now.toEpochMilli() ||
            target - now.toEpochMilli() > WarmupAlarmPlanner.PREPARATION_MAX_MILLIS ||
            !AlarmDispatchGuard.consumedTarget(target, generation, consumedTarget, consumedGeneration)) return null
        return AlarmPlanSelector.next(settings, plans.filter { it.date.atTime(it.time).atZone(zone).toInstant().toEpochMilli() == target }, records, now)
    }
}
