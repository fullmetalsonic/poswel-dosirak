package com.fullmetalsonic.dosirak.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

enum class ShiftType(val label: String) {
    A("A조"), B("B조"), C("C조"), D("D조"), REGULAR("일반상주"),
    ALTERNATE_A("격주휴무상주 A"), ALTERNATE_B("격주휴무상주 B")
}
enum class Shift(val label: String) { DAY("주"), NIGHT("야"), OFF("휴") }
enum class DatePolicy(val label: String) { AUTO("자동 설정 따르기"), MANUAL("수동 예약"), EXCLUDE("신청 제외") }
enum class ReservationReason(val label: String) { NONE("없음"), VACATION("휴가"), SUBSTITUTE("대근"), SUPPORT("지원"), OTHER("기타") }
enum class ExecutionStatus(val label: String) {
    PLANNED("예정"), PREPARING("준비중"), RUNNING("신청중"), COMPLETED("주문완료"),
    FAILED("실패"), NEEDS_CHECK("확인필요"), SKIPPED("미실행")
}
enum class LiveScope { NONE, SINGLE_DATE, RECURRING }

data class AppSettings(
    val masterEnabled: Boolean = false,
    val dayAutoEnabled: Boolean = false,
    val shiftType: ShiftType = ShiftType.A,
    val patternAnchor: LocalDate = LocalDate.of(2026, 12, 13),
    val patternConfirmed: Boolean = false,
    val weekdays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
    val defaultQuantity: Int = 1,
    val orderTime: LocalTime = LocalTime.of(6, 0, 0),
    val limitEnabled: Boolean = true,
    val unitLimit: Long? = null,
    val orderLimit: Long? = null,
    val retryCount: Int = 0,
    val retryIntervalSeconds: Int = 3,
    val preparationAlert: Boolean = false,
    val useStoredCredentials: Boolean = true,
    val mediaAlarmEnabled: Boolean = false,
    val mediaVolumePercent: Int = 70,
    val mediaDurationSeconds: Int = 15,
    val manufacturerSettingsConfirmed: Boolean = false,
    val liveScope: LiveScope = LiveScope.NONE,
    val liveTestDate: LocalDate? = null,
    val displayPriceRiskAccepted: Boolean = false,
    val accountGeneration: Long = 0,
    val verifiedLiveAccountGeneration: Long? = null,
    val liveBlockedReason: String? = null,
    val generation: Long = 0
)
data class DateOverride(
    val date: LocalDate,
    val policy: DatePolicy,
    val quantity: Int? = null,
    val time: LocalTime? = null,
    val reason: ReservationReason = ReservationReason.NONE
)
data class OrderPlan(val date: LocalDate, val quantity: Int, val time: LocalTime, val source: DatePolicy, val reason: ReservationReason)
data class ExecutionRecord(
    val date: LocalDate,
    val quantity: Int,
    val status: ExecutionStatus,
    val stage: String,
    val message: String,
    val updatedAt: Instant = Instant.now(),
    val amount: Long? = null,
    val serverOrderId: String? = null,
    val submissionPossible: Boolean = false,
    val accountGeneration: Long = 0,
    val generation: Long = 0
)
data class EnvironmentStatus(val key: String, val title: String, val status: String, val detail: String, val blocking: Boolean = false)

object ReservationLimits {
    fun validate(settings: AppSettings): String? = when {
        settings.defaultQuantity !in 1..5 -> "기본 수량은 1~5개로 입력하세요."
        settings.retryCount !in 0..10 -> "추가 재시도는 0~10회로 입력하세요."
        settings.retryIntervalSeconds !in 1..300 -> "재시도 간격은 1~300초로 입력하세요."
        settings.limitEnabled && (settings.unitLimit == null || settings.orderLimit == null || settings.unitLimit <= 0 || settings.orderLimit <= 0) -> "도시락 1개 최대 금액과 한 번에 주문할 최대 금액을 입력하세요."
        else -> null
    }
    fun amountAllowed(settings: AppSettings, quantity: Int, total: Long): Boolean {
        if (quantity !in 1..5 || total <= 0) return false
        if (!settings.limitEnabled) return true
        val p = settings.unitLimit ?: return false
        val c = settings.orderLimit ?: return false
        if (p <= 0 || c <= 0 || p > Long.MAX_VALUE / quantity) return false
        return total <= minOf(p * quantity, c)
    }
}
