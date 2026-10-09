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
}
