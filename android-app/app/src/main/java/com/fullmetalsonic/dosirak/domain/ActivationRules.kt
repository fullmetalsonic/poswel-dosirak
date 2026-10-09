package com.fullmetalsonic.dosirak.domain

import com.fullmetalsonic.dosirak.platform.AlarmPlanSelector
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ActivationRejected(message: String) : IllegalArgumentException(message)

object ActivationRules {
    private val zone = ZoneId.of("Asia/Seoul")

    fun maskAccount(userId: String): String = if (userId.length <= 2) "**"
        else userId.take(2) + "*".repeat(minOf(userId.length - 2, 8))

    fun requireGeneration(current: AppSettings, expectedGeneration: Long?) {
        checkRule(expectedGeneration == null || current.generation == expectedGeneration,
            "설정이 변경되었습니다. 최신 설정을 확인한 뒤 다시 저장하세요.")
    }

    fun editedSettings(current: AppSettings, draft: AppSettings): AppSettings = current.copy(
        dayAutoEnabled = draft.dayAutoEnabled,
        shiftType = draft.shiftType,
        patternAnchor = draft.patternAnchor,
        patternConfirmed = draft.patternConfirmed,
        weekdays = draft.weekdays.toSet(),
        defaultQuantity = draft.defaultQuantity,
        orderTime = draft.orderTime,
        limitEnabled = draft.limitEnabled,
        unitLimit = draft.unitLimit,
        orderLimit = draft.orderLimit,
        retryCount = draft.retryCount,
        retryIntervalSeconds = draft.retryIntervalSeconds,
        preparationAlert = draft.preparationAlert,
        useStoredCredentials = draft.useStoredCredentials,
        mediaAlarmEnabled = false,
        mediaVolumePercent = draft.mediaVolumePercent.coerceIn(0, 100),
        mediaDurationSeconds = draft.mediaDurationSeconds.coerceIn(1, 60),
        manufacturerSettingsConfirmed = draft.manufacturerSettingsConfirmed
    )

    fun settingsForSave(current: AppSettings, draft: AppSettings, expectedGeneration: Long?): AppSettings {
        requireGeneration(current, expectedGeneration)
        val next = editedSettings(current, draft).copy(masterEnabled = draft.masterEnabled,
            generation = current.generation + 1)
        ReservationLimits.validate(next.copy(limitEnabled = false))?.let { throw ActivationRejected(it) }
        if (next.masterEnabled) {
            checkRule(current.liveScope != LiveScope.NONE && current.displayPriceRiskAccepted,
                "실제구매 활성화 확인에서 구매 범위와 가격 변동 위험에 먼저 동의하세요.")
            ReservationLimits.validate(next)?.let { throw ActivationRejected(it) }
        }
        return next
    }

    fun recurringActivation(
        current: AppSettings,
        draft: AppSettings,
        expectedGeneration: Long,
        expectedAccountGeneration: Long,
        expectedAccountLabel: String,
        storedUserId: String?,
        acceptedPriceRisk: Boolean,
        overrides: Map<LocalDate, DateOverride>,
        records: List<ExecutionRecord>,
        now: Instant
    ): AppSettings {
        checkRule(acceptedPriceRisk, "반복 실제구매와 가격 변동 위험에 먼저 동의하세요.")
        requireGeneration(current, expectedGeneration)
        checkRule(current.accountGeneration == expectedAccountGeneration,
            "저장 계정이 변경되었습니다. 최신 계정으로 다시 활성화하세요.")
        checkRule(!storedUserId.isNullOrBlank(), "실제구매에 사용할 계정을 먼저 저장하세요.")
        checkRule(expectedAccountLabel.isNotBlank() && expectedAccountLabel == maskAccount(storedUserId!!),
            "확인한 계정과 현재 저장 계정이 다릅니다. 다시 확인하세요.")
        val next = editedSettings(current, draft).copy(masterEnabled = true,
            liveScope = LiveScope.RECURRING, liveTestDate = null, displayPriceRiskAccepted = true,
            generation = current.generation + 1)
        ReservationLimits.validate(next)?.let { throw ActivationRejected(it) }
        checkRule(next.liveBlockedReason == null, "실제구매가 차단되어 있습니다. 차단 사유와 주문내역을 먼저 확인하세요.")
        checkRule(nextExecutablePlan(next, overrides, records, now) != null,
            "06:00 이상 08:00 미만에 실행할 미래 신청 계획이 없습니다. 근무 확인·요일·날짜 예외를 확인하세요.")
        return next
    }

    fun nextExecutablePlan(settings: AppSettings, overrides: Map<LocalDate, DateOverride>,
        records: List<ExecutionRecord>, now: Instant): OrderPlan? {
        val plans = ScheduleCalculator.upcomingPlans(settings, overrides, now.atZone(zone).toLocalDate())
            .filter { it.time >= LocalTime.of(6, 0) && it.time < LocalTime.of(8, 0) }
        return AlarmPlanSelector.next(settings, plans, records, now)
    }

    fun stoppedSettings(current: AppSettings): AppSettings =
        current.copy(masterEnabled = false, generation = current.generation + 1)

    private fun checkRule(condition: Boolean, message: String) {
        if (!condition) throw ActivationRejected(message)
    }
}
