package com.fullmetalsonic.dosirak.domain

import java.time.LocalDate
import java.util.UUID

data class WorkTypeDefinition(
    val id: String,
    val name: String,
    val calendarLabel: String,
    val color: Long,
    val mealDefault: Boolean?,
    val favorite: Boolean,
    val sortOrder: Int,
    val archived: Boolean = false
)

class CalendarEditConflict : IllegalStateException("일정이 변경되었습니다. 다시 열어 주세요.")

object WorkTypes {
    const val DAY = "day"
    const val NIGHT = "night"
    const val DAY_SUBSTITUTE = "day_substitute"
    const val NIGHT_SUBSTITUTE = "night_substitute"
    const val EDUCATION = "education"
    const val VACATION = "vacation"
    const val OFF = "off"
    const val SUPPORT = "support"
    const val BUSINESS_TRIP = "business_trip"
    const val RESIDENT = "resident"
    const val AFTERNOON_EDUCATION = "afternoon_education"
    const val DINNER = "dinner"
    const val VOLUNTEER = "volunteer"
    const val MAX_NAME_LENGTH = 20
    const val MAX_LABEL_LENGTH = 4
    const val MAX_CUSTOM_TYPES = 30

    val builtIns: List<WorkTypeDefinition> = listOf(
        WorkTypeDefinition(DAY, "주간", "주", 0xFF00659E, true, true, 0),
        WorkTypeDefinition(NIGHT, "야간", "야", 0xFF47515C, false, true, 1),
        WorkTypeDefinition(OFF, "휴무", "휴", 0xFF47515C, false, true, 2),
        WorkTypeDefinition(DAY_SUBSTITUTE, "주간대근", "주대", 0xFF00659E, true, true, 3),
        WorkTypeDefinition(NIGHT_SUBSTITUTE, "야간대근", "야대", 0xFF47515C, false, true, 4),
        WorkTypeDefinition(VACATION, "휴가", "휴가", 0xFF417452, false, true, 5),
        WorkTypeDefinition(EDUCATION, "교육", "교육", 0xFF6B4E9B, null, true, 6),
        WorkTypeDefinition(SUPPORT, "지원", "지원", 0xFF596C64, null, false, 7),
        WorkTypeDefinition(BUSINESS_TRIP, "출장", "출장", 0xFF496D9A, null, false, 8),
        WorkTypeDefinition(RESIDENT, "상주", "상주", 0xFF00659E, true, false, 9),
        WorkTypeDefinition(AFTERNOON_EDUCATION, "오후교육", "오후교육", 0xFF637536, null, false, 10),
        WorkTypeDefinition(DINNER, "회식", "회식", 0xFF866447, null, false, 11),
        WorkTypeDefinition(VOLUNTEER, "봉사활동", "봉사활동", 0xFF8B4D8F, null, false, 12)
    )
    private val builtInIds = builtIns.map { it.id }.toSet()

    fun isBuiltIn(id: String): Boolean = id in builtInIds

    // Gson may leave an added non-null field null when reading an older settings object.
    fun normalized(settings: AppSettings): AppSettings {
        val supplied: List<WorkTypeDefinition?>? = settings.workTypes
        if (supplied == null) return settings.copy(workTypes = builtIns)
        var customCount = 0
        val valid = supplied.filterNotNull().filter { definitionError(it) == null }.distinctBy { it.id }
            .filter { isBuiltIn(it.id) || customCount++ < MAX_CUSTOM_TYPES }
        val missing = builtIns.filter { builtin -> valid.none { it.id == builtin.id } }
        val result = valid + missing
        return if (result == supplied) settings else settings.copy(workTypes = result)
    }

    fun definition(id: String, settings: AppSettings): WorkTypeDefinition? =
        normalized(settings).workTypes.firstOrNull { it.id == id }

    fun baseOn(date: LocalDate, settings: AppSettings): WorkTypeDefinition {
        val id = when (ScheduleCalculator.shiftOn(date, settings)) {
            Shift.DAY -> if (settings.shiftType in setOf(ShiftType.REGULAR, ShiftType.ALTERNATE_A, ShiftType.ALTERNATE_B)) RESIDENT else DAY
            Shift.NIGHT -> NIGHT
            Shift.OFF -> OFF
        }
        return definition(id, settings) ?: builtIns.first { it.id == id }
    }

    fun validate(types: List<WorkTypeDefinition>): String? {
        if (types.size > builtIns.size + MAX_CUSTOM_TYPES) return "추가 근무는 30개까지 만들 수 있습니다."
        val entries: List<WorkTypeDefinition?> = types
        if (entries.any { it == null }) return "근무 종류가 올바르지 않습니다."
        val defined = entries.filterNotNull()
        defined.forEach { definitionError(it)?.let { error -> return error } }
        if (defined.map { it.id }.distinct().size != defined.size) return "근무 종류가 중복되었습니다."
        if (!defined.map { it.id }.containsAll(builtInIds)) return "기본 근무는 삭제할 수 없습니다."
        return null
    }

    private fun definitionError(type: WorkTypeDefinition): String? {
        val id: String? = type.id
        val name: String? = type.name
        val label: String? = type.calendarLabel
        if (id == null || (!isBuiltIn(id) && !runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))) return "근무 종류 ID가 올바르지 않습니다."
        if (name.isNullOrBlank() || name != name.trim() || name.codePointCount(0, name.length) > MAX_NAME_LENGTH || name.any { it.isISOControl() }) return "근무 이름은 1~20자로 입력하세요."
        if (label.isNullOrBlank() || label != label.trim() || label.codePointCount(0, label.length) > MAX_LABEL_LENGTH || label.any { it.isISOControl() }) return "달력 표시는 1~4자로 입력하세요."
        if (type.color !in 0xFF000000L..0xFFFFFFFFL) return "근무 색상을 다시 선택해 주세요."
        if (type.sortOrder !in 0..10000) return "근무 순서가 올바르지 않습니다."
        return null
    }
}
