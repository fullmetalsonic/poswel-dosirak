package com.fullmetalsonic.dosirak.ui

import com.fullmetalsonic.dosirak.domain.*
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal data class SetupIssue(val label: String, val step: Int)
internal fun validOrderTime(time: LocalTime): Boolean = time >= LocalTime.of(6, 0) && time < LocalTime.of(8, 0)
internal fun futureUiPlans(settings: AppSettings, state: UiState, now: ZonedDateTime = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))): List<OrderPlan> =
    listOfNotNull(ActivationRules.nextExecutablePlan(settings.copy(masterEnabled = true, liveScope = LiveScope.RECURRING,
        displayPriceRiskAccepted = true), state.overrides, state.records, now.toInstant()))

internal fun setupIssues(settings: AppSettings, state: UiState): List<SetupIssue> = buildList {
    if (!state.credentialsSaved) add(SetupIssue("계정 저장", 0))
    if (state.credentialsSaved && state.accountLabel.isBlank()) add(SetupIssue("저장된 계정 확인", 0))
    if (settings.dayAutoEnabled && !settings.patternConfirmed) add(SetupIssue("실제 근무표 확인", 1))
    if (settings.defaultQuantity !in 1..5) add(SetupIssue("수량을 1~5개로 입력", 2))
    if (!validOrderTime(settings.orderTime)) add(SetupIssue("신청 시각을 06:00:00~07:59:59로 입력", 2))
    if (settings.limitEnabled && ((settings.unitLimit ?: 0) <= 0 || (settings.orderLimit ?: 0) <= 0)) add(SetupIssue("도시락·주문 최대금액 입력", 2))
    if (settings.retryCount !in 0..10 || settings.retryIntervalSeconds !in 1..300) add(SetupIssue("다시 시도 횟수와 대기시간 확인", 2))
    state.environment.filter { it.blocking }.forEach { add(SetupIssue("${it.title}: ${it.status}", 3)) }
    if (state.settings.liveBlockedReason != null) add(SetupIssue("실행 차단 사유 검토", 3))
    if (futureUiPlans(settings, state).isEmpty()) add(SetupIssue("앞으로 신청할 자동·수동 예약 설정", -1))
}

internal fun AppSettings.editableOnly(): AppSettings = copy(masterEnabled = false, liveScope = LiveScope.NONE,
    liveTestDate = null, displayPriceRiskAccepted = false, accountGeneration = 0,
    verifiedLiveAccountGeneration = null, liveBlockedReason = null, generation = 0)
