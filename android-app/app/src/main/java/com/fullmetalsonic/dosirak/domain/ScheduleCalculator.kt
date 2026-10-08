package com.fullmetalsonic.dosirak.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object ScheduleCalculator {
    private val cycle = listOf(Shift.DAY, Shift.DAY, Shift.OFF, Shift.OFF, Shift.NIGHT, Shift.NIGHT, Shift.OFF, Shift.OFF)
    // Existing app rules; these anchors do not confirm the user's personal roster.
    private val alternateAAnchor = LocalDate.of(2026, 8, 14)
    private val alternateBAnchor = LocalDate.of(2026, 8, 7)

    fun shiftOn(date: LocalDate, settings: AppSettings): Shift {
        val offset = when (settings.shiftType) {
            ShiftType.A -> 2
            ShiftType.B -> 0
            ShiftType.C -> 4
            ShiftType.D -> 6
            else -> null
        }
        if (offset != null) {
            val elapsed = ChronoUnit.DAYS.between(settings.patternAnchor, date)
            return cycle[Math.floorMod(elapsed + offset, cycle.size.toLong()).toInt()]
        }
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY || holidayName(date) != null) {
            return Shift.OFF
        }
        val fridayAnchor = when (settings.shiftType) {
            ShiftType.ALTERNATE_A -> alternateAAnchor
            ShiftType.ALTERNATE_B -> alternateBAnchor
            else -> null
        }
        if (fridayAnchor != null && date.dayOfWeek == DayOfWeek.FRIDAY &&
            Math.floorMod(ChronoUnit.DAYS.between(fridayAnchor, date), 14L) == 0L
        ) return Shift.OFF
        return Shift.DAY
    }

    // Plans describe the calendar. Execution separately checks the master switch.
    fun planFor(date: LocalDate, settings: AppSettings, override: DateOverride?): OrderPlan? {
        if (override != null && override.date != date) return null
        when (override?.policy) {
            DatePolicy.EXCLUDE -> return null
            DatePolicy.MANUAL -> {
                val quantity = override.quantity ?: settings.defaultQuantity
                if (quantity !in 1..5) return null
                return OrderPlan(date, quantity, override.time ?: settings.orderTime, DatePolicy.MANUAL, override.reason)
            }
            else -> Unit
        }
        if (!settings.dayAutoEnabled || !settings.patternConfirmed || settings.defaultQuantity !in 1..5) return null
        if (isResident(settings.shiftType) && (!knownHolidayYear(date.year) || date.dayOfWeek !in settings.weekdays)) return null
        if (shiftOn(date, settings) != Shift.DAY) return null
        return OrderPlan(date, settings.defaultQuantity, settings.orderTime, DatePolicy.AUTO, ReservationReason.NONE)
    }

    fun holidayName(date: LocalDate): String? = KoreanHolidays.nameOn(date)

    fun knownHolidayYear(year: Int): Boolean = KoreanHolidays.knownYear(year)

    fun upcomingPlans(
        settings: AppSettings,
        overrides: Map<LocalDate, DateOverride>,
        today: LocalDate,
        days: Int = 93
    ): List<OrderPlan> {
        require(days >= 0) { "days must not be negative" }
        return (0 until days).mapNotNull { offset ->
            val date = today.plusDays(offset.toLong())
            planFor(date, settings, overrides[date])
        }
    }

    private fun isResident(type: ShiftType): Boolean = type == ShiftType.REGULAR ||
        type == ShiftType.ALTERNATE_A || type == ShiftType.ALTERNATE_B
}
