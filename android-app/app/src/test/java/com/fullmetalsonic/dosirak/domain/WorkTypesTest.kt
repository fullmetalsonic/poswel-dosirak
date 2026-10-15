package com.fullmetalsonic.dosirak.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class WorkTypesTest {
    private val date = LocalDate.of(2026, 10, 15)
    private val settings = AppSettings(dayAutoEnabled = true, patternConfirmed = true, limitEnabled = false)

    @Test fun thirteenStableBuiltInsAndValidationBoundaries() {
        assertEquals(13, WorkTypes.builtIns.size)
        assertEquals(13, WorkTypes.builtIns.map { it.id }.distinct().size)
        assertNull(WorkTypes.validate(WorkTypes.builtIns))
        assertEquals(true, WorkTypes.definition(WorkTypes.DAY_SUBSTITUTE, settings)?.mealDefault)
        assertEquals(false, WorkTypes.definition(WorkTypes.VACATION, settings)?.mealDefault)
        assertNull(WorkTypes.definition(WorkTypes.EDUCATION, settings)?.mealDefault)
        val custom = WorkTypeDefinition(UUID.randomUUID().toString(), "현장교육", "현교", 0xFF365CB4, null, true, 13)
        assertNull(WorkTypes.validate(WorkTypes.builtIns + custom))
        assertNotNull(WorkTypes.validate(WorkTypes.builtIns + custom.copy(calendarLabel = "다섯글자표시")))
        assertNotNull(WorkTypes.validate(WorkTypes.builtIns + custom.copy(id = "bad")))
        assertNotNull(WorkTypes.validate(WorkTypes.builtIns + custom.copy(color = 0x123456)))
        assertNotNull(WorkTypes.validate(WorkTypes.builtIns.drop(1)))
        assertNotNull(WorkTypes.validate(WorkTypes.builtIns + List(31) { custom.copy(id = UUID.randomUUID().toString()) }))
    }

    @Test fun actualWorkDoesNotMoveAnyBaseShiftPattern() {
        ShiftType.entries.forEach { type ->
            val config = settings.copy(shiftType = type)
            (-20..20).forEach { offset ->
                val selectedDate = date.plusDays(offset.toLong())
                val shift = ScheduleCalculator.shiftOn(selectedDate, config)
                val exception = DateOverride(selectedDate, DatePolicy.MANUAL, workTypeId = WorkTypes.DAY_SUBSTITUTE)
                assertEquals(WorkTypes.DAY_SUBSTITUTE, ScheduleCalculator.workOn(selectedDate, config, exception)?.id)
                assertEquals(shift, ScheduleCalculator.shiftOn(selectedDate, config))
                assertEquals(WorkTypes.baseOn(selectedDate, config), ScheduleCalculator.workOn(selectedDate, config, null))
            }
        }
    }

    @Test fun savedMealIntentDoesNotChangeWithCatalogDefaultOrBasePattern() {
        val manual = DateOverride(date, DatePolicy.MANUAL, workTypeId = WorkTypes.VACATION)
        val excluded = manual.copy(policy = DatePolicy.EXCLUDE, workTypeId = WorkTypes.DAY_SUBSTITUTE)
        val modified = settings.copy(defaultQuantity = 3, orderTime = LocalTime.of(6, 5), shiftType = ShiftType.D,
            workTypes = settings.workTypes.map { it.copy(mealDefault = false) })
        assertEquals(3, ScheduleCalculator.planFor(date, modified, manual)?.quantity)
        assertEquals(LocalTime.of(6, 5), ScheduleCalculator.planFor(date, modified, manual)?.time)
        assertNull(ScheduleCalculator.planFor(date, modified, excluded))
        val explicit = manual.copy(quantity = 2, time = LocalTime.of(6, 20))
        assertEquals(2, ScheduleCalculator.planFor(date, modified, explicit)?.quantity)
        assertEquals(LocalTime.of(6, 20), ScheduleCalculator.planFor(date, modified, explicit)?.time)
    }

    @Test fun unresolvedWorkDoesNotSilentlyCreateAutoButRetainsExplicitMealIntent() {
        val day = (0L..7L).map { date.plusDays(it) }.first { ScheduleCalculator.shiftOn(it, settings) == Shift.DAY }
        val unknown = DateOverride(day, DatePolicy.AUTO, workTypeId = "missing-id")
        assertNull(ScheduleCalculator.workOn(day, settings, unknown))
        assertNull(ScheduleCalculator.planFor(day, settings, unknown))
        assertNotNull(ScheduleCalculator.planFor(day, settings, unknown.copy(policy = DatePolicy.MANUAL)))
        assertNull(ScheduleCalculator.planFor(day, settings, unknown.copy(policy = DatePolicy.EXCLUDE)))
        assertNull(ScheduleCalculator.planFor(day, settings, unknown.copy(workTypeId = WorkTypes.VACATION)))
    }

    @Test fun archivedDefinitionsRemainResolvableWithoutChangingPlans() {
        val archived = settings.copy(workTypes = settings.workTypes.map { if (it.id == WorkTypes.EDUCATION) it.copy(archived = true) else it })
        val override = DateOverride(date, DatePolicy.MANUAL, 2, workTypeId = WorkTypes.EDUCATION)
        assertTrue(ScheduleCalculator.workOn(date, archived, override)!!.archived)
        assertEquals(2, ScheduleCalculator.planFor(date, archived, override)?.quantity)
    }

    @Test fun malformedNullableCatalogEntriesNormalizeWithoutReinterpretingDatedMealIntent() {
        val gson = com.fullmetalsonic.dosirak.data.AppJson.gson
        val root = com.google.gson.JsonParser.parseString(gson.toJson(settings)).asJsonObject
        val customId = UUID.randomUUID().toString()
        root.add("workTypes", com.google.gson.JsonParser.parseString("""[null,{"id":"$customId","name":null,"calendarLabel":"X","color":4278190080,"mealDefault":true,"favorite":true,"sortOrder":13}]"""))
        val normalized = WorkTypes.normalized(gson.fromJson(root, AppSettings::class.java))
        assertEquals(WorkTypes.builtIns, normalized.workTypes)
        val explicit = DateOverride(date, DatePolicy.MANUAL, 2, workTypeId = customId)
        assertNull(ScheduleCalculator.workOn(date, normalized, explicit))
        assertEquals(2, ScheduleCalculator.planFor(date, normalized, explicit)?.quantity)
        assertNull(ScheduleCalculator.planFor(date, normalized, explicit.copy(policy = DatePolicy.AUTO)))
    }
}
