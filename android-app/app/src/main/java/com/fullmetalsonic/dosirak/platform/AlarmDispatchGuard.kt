package com.fullmetalsonic.dosirak.platform

import java.time.LocalDate

internal data class AlarmDispatchKey(val date: LocalDate, val generation: Long)

internal object AlarmDispatchGuard {
    fun isCurrent(alarmGeneration: Long?, currentGeneration: Long): Boolean =
        alarmGeneration != null && alarmGeneration >= 0L && alarmGeneration == currentGeneration

    fun key(date: LocalDate?, generation: Long?): AlarmDispatchKey? =
        if (date == null || generation == null || generation < 0L) null else AlarmDispatchKey(date, generation)

    fun shouldEnqueue(key: AlarmDispatchKey, active: AlarmDispatchKey?, pending: Iterable<AlarmDispatchKey>): Boolean =
        key != active && pending.none { it == key }

    fun activeFlight(generation: Long, currentGeneration: Long, startedElapsed: Long, untilElapsed: Long, nowElapsed: Long): Boolean =
        generation >= 0L && generation == currentGeneration && startedElapsed >= 0L && untilElapsed > startedElapsed &&
            untilElapsed - startedElapsed <= WarmupAlarmPlanner.SERVICE_MAX_MILLIS && nowElapsed >= startedElapsed && nowElapsed < untilElapsed

    fun consumedTarget(target: Long, generation: Long, consumedTarget: Long, consumedGeneration: Long): Boolean =
        target > 0L && generation >= 0L && target == consumedTarget && generation == consumedGeneration
}
