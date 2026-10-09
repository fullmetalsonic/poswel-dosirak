package com.fullmetalsonic.dosirak.platform

internal data class WarmupAlarmPlan(
    val targetEpochMillis: Long,
    val targetWallEpochMillis: Long,
    val wakeEpochMillis: Long,
    val warmup: Boolean,
    val readiness: String
)

internal object WarmupAlarmPlanner {
    const val LEAD_MILLIS = 120_000L
    const val PREPARATION_MAX_MILLIS = 180_000L
    const val SERVICE_MAX_MILLIS = 600_000L

    fun plan(target: Long, logicalNow: Long, wallNow: Long, batteryExempt: Boolean): WarmupAlarmPlan? {
        if (target <= logicalNow) return null
        return try {
            val remaining = Math.subtractExact(target, logicalNow)
            val targetWall = Math.addExact(wallNow, remaining)
            val warmup = batteryExempt && remaining >= LEAD_MILLIS
            WarmupAlarmPlan(target, targetWall, if (warmup) targetWall - LEAD_MILLIS else targetWall, warmup,
                when {
                    !batteryExempt -> "사전대기 미준비 · 배터리 최적화 면제가 없어 목표 시각에 시작합니다."
                    !warmup -> "사전대기 미준비 · 120초 전 시각이 지나 목표 시각에 시작합니다."
                    else -> "사전대기 준비됨 · 목표 시각 120초 전에 시작합니다."
                })
        } catch (_: ArithmeticException) { null }
    }

    fun restore(target: Long, targetWall: Long, wake: Long, wallNow: Long, batteryExempt: Boolean): WarmupAlarmPlan? {
        if (target <= 0L || targetWall <= wallNow) return null
        val warmup = batteryExempt && wake > wallNow && wake < targetWall
        return WarmupAlarmPlan(target, targetWall, if (warmup) wake else targetWall, warmup,
            if (warmup) "사전대기 준비됨 · 미래 실행 알람을 복구했습니다."
            else "사전대기 미준비 · 목표 시각의 미래 실행 알람만 복구했습니다.")
    }
}
