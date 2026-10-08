package com.fullmetalsonic.dosirak.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleCalculatorTest {
    private val anchor = LocalDate.of(2026, 12, 13)
    private val active = AppSettings(dayAutoEnabled = true, patternConfirmed = true, shiftType = ShiftType.B)

    @Test fun fourTeamsFollowTheEightDayCycleAndOffsets() {
        val cycle = listOf(Shift.DAY, Shift.DAY, Shift.OFF, Shift.OFF, Shift.NIGHT, Shift.NIGHT, Shift.OFF, Shift.OFF)
        val offsets = mapOf(ShiftType.A to 2, ShiftType.B to 0, ShiftType.C to 4, ShiftType.D to 6)
        offsets.forEach { (team, offset) ->
            (-17..17).forEach { day ->
                assertEquals("$team at $day", cycle[Math.floorMod(day + offset, 8)],
                    ScheduleCalculator.shiftOn(anchor.plusDays(day.toLong()), active.copy(shiftType = team)))
            }
        }
    }

    @Test fun confirmedCustomAnchorAndLeapDayUseWholeCalendarDays() {
        val leapAnchor = LocalDate.of(2024, 2, 28)
        val settings = active.copy(patternAnchor = leapAnchor)
        assertEquals(Shift.DAY, ScheduleCalculator.shiftOn(LocalDate.of(2024, 2, 29), settings))
        assertEquals(Shift.OFF, ScheduleCalculator.shiftOn(LocalDate.of(2024, 3, 1), settings))
        assertEquals(Shift.OFF, ScheduleCalculator.shiftOn(leapAnchor.minusDays(1), settings))
        assertEquals(Shift.DAY, ScheduleCalculator.shiftOn(leapAnchor.minusDays(7), settings))
        assertNotNull(ScheduleCalculator.planFor(leapAnchor, settings, null))
    }

    @Test fun regularResidentUsesWeekdaysAndNationalHolidays() {
        val settings = active.copy(shiftType = ShiftType.REGULAR)
        assertEquals(Shift.DAY, ScheduleCalculator.shiftOn(LocalDate.of(2026, 8, 10), settings))
        assertEquals(Shift.OFF, ScheduleCalculator.shiftOn(LocalDate.of(2026, 8, 8), settings))
        assertEquals(Shift.OFF, ScheduleCalculator.shiftOn(LocalDate.of(2026, 8, 9), settings))
        assertEquals(Shift.OFF, ScheduleCalculator.shiftOn(LocalDate.of(2026, 8, 17), settings))
    }

    @Test fun bothAlternateResidentsHonorOriginalFridayAnchorsInBothDirections() {
        val a = active.copy(shiftType = ShiftType.ALTERNATE_A)
        val b = active.copy(shiftType = ShiftType.ALTERNATE_B)
        listOf("2026-07-31", "2026-08-14", "2026-08-28").forEach {
            val date = LocalDate.parse(it)
            assertEquals(Shift.OFF, ScheduleCalculator.shiftOn(date, a))
            assertEquals(Shift.DAY, ScheduleCalculator.shiftOn(date, b))
        }
        listOf("2026-07-24", "2026-08-07", "2026-08-21").forEach {
            val date = LocalDate.parse(it)
            assertEquals(Shift.DAY, ScheduleCalculator.shiftOn(date, a))
            assertEquals(Shift.OFF, ScheduleCalculator.shiftOn(date, b))
        }
        listOf(a, b).forEach { settings ->
            assertEquals(Shift.OFF, ScheduleCalculator.shiftOn(LocalDate.of(2026, 10, 9), settings))
            assertEquals(Shift.OFF, ScheduleCalculator.shiftOn(LocalDate.of(2026, 8, 8), settings))
        }
    }

    @Test fun publicHolidayDatesIncludeElectionAndThe2026Amendment() {
        val expected = mapOf(
            "2026-02-16" to "설날 연휴", "2026-02-17" to "설날", "2026-02-18" to "설날 연휴",
            "2026-03-02" to "대체공휴일(삼일절)", "2026-05-01" to "노동절",
            "2026-05-25" to "대체공휴일(부처님오신날)", "2026-06-03" to "전국동시지방선거",
            "2026-07-17" to "제헌절", "2026-08-17" to "대체공휴일(광복절)",
            "2026-09-24" to "추석 연휴", "2026-09-25" to "추석", "2026-09-26" to "추석 연휴",
            "2026-10-05" to "대체공휴일(개천절)", "2027-02-09" to "대체공휴일(설날)",
            "2027-05-03" to "대체공휴일(노동절)", "2027-05-13" to "부처님오신날",
            "2027-07-19" to "대체공휴일(제헌절)", "2027-08-16" to "대체공휴일(광복절)",
            "2027-10-04" to "대체공휴일(개천절)", "2027-10-11" to "대체공휴일(한글날)",
            "2027-12-27" to "대체공휴일(기독탄신일)"
        )
        expected.forEach { (date, name) -> assertEquals(name, ScheduleCalculator.holidayName(LocalDate.parse(date))) }
        assertNull(ScheduleCalculator.holidayName(LocalDate.of(2026, 9, 28)))
        assertNull(ScheduleCalculator.holidayName(LocalDate.of(2027, 6, 7)))
        assertNull(ScheduleCalculator.holidayName(LocalDate.of(2026, 6, 8)))
    }

    @Test fun publishedHolidayCountsMatchOfficialListsWithoutWeekends() {
        fun count(year: Int) = (0 until LocalDate.of(year, 1, 1).lengthOfYear()).count { day ->
            ScheduleCalculator.holidayName(LocalDate.of(year, 1, 1).plusDays(day.toLong())) != null
        }
        assertEquals(22, count(2026))
        assertEquals(24, count(2027))
    }

    @Test fun shiftDayRemainsEligibleOnHolidayAndIgnoresResidentWeekdaySelection() {
        val holiday = LocalDate.of(2026, 10, 9)
        val settings = active.copy(patternAnchor = holiday, weekdays = emptySet())
        assertEquals(Shift.DAY, ScheduleCalculator.shiftOn(holiday, settings))
        assertNotNull(ScheduleCalculator.planFor(holiday, settings, null))
    }

    @Test fun autoRequiresConfirmationAndDaySwitchAndActualDayShift() {
        assertNull(ScheduleCalculator.planFor(anchor, active.copy(patternConfirmed = false), null))
        assertNull(ScheduleCalculator.planFor(anchor, active.copy(dayAutoEnabled = false), null))
        assertNull(ScheduleCalculator.planFor(anchor.plusDays(2), active, null))
        assertNull(ScheduleCalculator.planFor(anchor.plusDays(4), active, null))
        assertNotNull(ScheduleCalculator.planFor(anchor, active, null))
    }

    @Test fun masterOffPreservesAutoAndManualPreview() {
        val auto = ScheduleCalculator.planFor(anchor, active.copy(masterEnabled = false), null)
        assertNotNull(auto)
        assertEquals(auto, ScheduleCalculator.planFor(anchor, active.copy(masterEnabled = true), null))
        val manual = DateOverride(anchor.plusDays(4), DatePolicy.MANUAL, quantity = 2)
        assertNotNull(ScheduleCalculator.planFor(manual.date, AppSettings(masterEnabled = false), manual))
    }

    @Test fun residentSelectedWeekdaysMustAlsoBeActualWorkdays() {
        val wednesday = LocalDate.of(2026, 8, 12)
        val settings = active.copy(shiftType = ShiftType.REGULAR)
        assertNotNull(ScheduleCalculator.planFor(wednesday, settings, null))
        assertNull(ScheduleCalculator.planFor(wednesday, settings.copy(weekdays = setOf(DayOfWeek.MONDAY)), null))
        assertNull(ScheduleCalculator.planFor(wednesday, settings.copy(weekdays = emptySet()), null))
        val saturday = LocalDate.of(2026, 8, 8)
        assertNull(ScheduleCalculator.planFor(saturday, settings.copy(weekdays = setOf(DayOfWeek.SATURDAY)), null))
        val manual = DateOverride(wednesday, DatePolicy.MANUAL, 2, reason = ReservationReason.SUPPORT)
        assertNotNull(ScheduleCalculator.planFor(wednesday, settings.copy(weekdays = emptySet()), manual))
    }

    @Test fun unknownHolidayYearBlocksResidentAutoButKeepsManualAndRotatingShift() {
        assertTrue(ScheduleCalculator.knownHolidayYear(2026))
        assertTrue(ScheduleCalculator.knownHolidayYear(2027))
        assertFalse(ScheduleCalculator.knownHolidayYear(2025))
        assertFalse(ScheduleCalculator.knownHolidayYear(2028))
        val date = LocalDate.of(2028, 1, 3)
        listOf(ShiftType.REGULAR, ShiftType.ALTERNATE_A, ShiftType.ALTERNATE_B).forEach { type ->
            val settings = active.copy(shiftType = type)
            assertNull(ScheduleCalculator.planFor(date, settings, null))
            assertNotNull(ScheduleCalculator.planFor(date, settings, DateOverride(date, DatePolicy.MANUAL, 1)))
        }
        assertNotNull(ScheduleCalculator.planFor(date, active.copy(patternAnchor = date), null))
        assertNull(ScheduleCalculator.holidayName(date))
    }

    @Test fun manualQuantityTimeAndReasonSurviveSettingsRecalculation() {
        val date = anchor.plusDays(4)
        val time = LocalTime.of(5, 59, 37)
        val manual = DateOverride(date, DatePolicy.MANUAL, 2, time, ReservationReason.SUBSTITUTE)
        val expected = OrderPlan(date, 2, time, DatePolicy.MANUAL, ReservationReason.SUBSTITUTE)
        assertEquals(expected, ScheduleCalculator.planFor(date, active, manual))
        assertEquals(expected, ScheduleCalculator.planFor(date,
            active.copy(dayAutoEnabled = false, patternConfirmed = false, shiftType = ShiftType.REGULAR,
                defaultQuantity = 5, orderTime = LocalTime.of(8, 0)), manual))
    }

    @Test fun manualNullFieldsUseCurrentDefaultsAndExcludeAlwaysWins() {
        val settings = active.copy(defaultQuantity = 3, orderTime = LocalTime.of(6, 15, 42))
        val manual = DateOverride(anchor, DatePolicy.MANUAL, reason = ReservationReason.SUPPORT)
        assertEquals(OrderPlan(anchor, 3, settings.orderTime, DatePolicy.MANUAL, ReservationReason.SUPPORT),
            ScheduleCalculator.planFor(anchor, settings, manual))
        val exclude = DateOverride(anchor, DatePolicy.EXCLUDE, quantity = 4, reason = ReservationReason.VACATION)
        assertNull(ScheduleCalculator.planFor(anchor, settings, exclude))
        assertNull(ScheduleCalculator.planFor(anchor, settings.copy(shiftType = ShiftType.REGULAR), exclude))
        assertNotNull(ScheduleCalculator.planFor(anchor, settings, exclude.copy(policy = DatePolicy.AUTO)))
    }

    @Test fun autoPolicyIsExactlyTheSameAsNoOverride() {
        val staleFields = DateOverride(anchor, DatePolicy.AUTO, 4, LocalTime.of(7, 15), ReservationReason.VACATION)
        assertEquals(ScheduleCalculator.planFor(anchor, active, null), ScheduleCalculator.planFor(anchor, active, staleFields))
        assertNull(ScheduleCalculator.planFor(anchor, active.copy(dayAutoEnabled = false), staleFields))
    }

    @Test fun invalidQuantitiesAndMismatchedDatesNeverProducePlans() {
        listOf(Int.MIN_VALUE, -1, 0, 6, Int.MAX_VALUE).forEach { quantity ->
            assertNull(ScheduleCalculator.planFor(anchor, active.copy(defaultQuantity = quantity), null))
            assertNull(ScheduleCalculator.planFor(anchor, active, DateOverride(anchor, DatePolicy.MANUAL, quantity)))
        }
        listOf(1, 5).forEach { quantity ->
            assertEquals(quantity, ScheduleCalculator.planFor(anchor, active.copy(defaultQuantity = quantity), null)?.quantity)
        }
        assertNull(ScheduleCalculator.planFor(anchor, active, DateOverride(anchor.plusDays(1), DatePolicy.MANUAL, 2)))
        assertNotNull(ScheduleCalculator.planFor(anchor, active.copy(defaultQuantity = 0), DateOverride(anchor, DatePolicy.MANUAL, 2)))
    }

    @Test fun upcomingPlansUseAnOrderedExclusiveWindowAcrossYearEnd() {
        val today = LocalDate.of(2026, 12, 31)
        val dates = listOf(today.minusDays(1), today, today.plusDays(1), today.plusDays(3))
        val overrides = dates.associateWith { DateOverride(it, DatePolicy.MANUAL, 2) }
        val settings = active.copy(dayAutoEnabled = false)
        val plans = ScheduleCalculator.upcomingPlans(settings, overrides, today, 3)
        assertEquals(listOf(today, today.plusDays(1)), plans.map { it.date })
        assertTrue(plans.all { it.quantity == 2 })
        assertTrue(ScheduleCalculator.upcomingPlans(settings, overrides, today, 0).isEmpty())
        assertEquals(4, overrides.size)
        val allDays = (0 until 94).associate { day ->
            val date = today.plusDays(day.toLong())
            date to DateOverride(date, DatePolicy.MANUAL, 1)
        }
        assertEquals(93, ScheduleCalculator.upcomingPlans(settings, allDays, today).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun upcomingWindowRejectsNegativeLength() {
        ScheduleCalculator.upcomingPlans(active, emptyMap(), anchor, -1)
    }
}
