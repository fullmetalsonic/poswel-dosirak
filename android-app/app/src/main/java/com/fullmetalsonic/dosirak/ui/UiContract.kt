package com.fullmetalsonic.dosirak.ui

import com.fullmetalsonic.dosirak.domain.*
import java.time.LocalDate
import com.fullmetalsonic.dosirak.update.UpdateStatus

data class SettingsSaveResult(val requestId: Long, val success: Boolean, val message: String? = null)

data class UiState(
    val settings: AppSettings = AppSettings(),
    val overrides: Map<LocalDate, DateOverride> = emptyMap(),
    val records: List<ExecutionRecord> = emptyList(),
    val environment: List<EnvironmentStatus> = emptyList(),
    val credentialsSaved: Boolean = false,
    val accountLabel: String = "",
    val loginVerified: Boolean = false,
    val registrationMessage: String? = null,
    val registrationProblem: Boolean = false,
    val settingsSaveResult: SettingsSaveResult? = null,
    val backgroundLastCheck: String = "아직 점검하지 않음",
    val backgroundNextCheck: String = "점검 꺼짐",
    val backgroundCheckSummary: String? = null,
    val currentVersion: String = "",
    val updateStatus: UpdateStatus = UpdateStatus(),
    val hasVerifiedLiveOrder: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val transientMessage: Boolean = false,
    val onboardingRequired: Boolean = false,
    val orderLookups: Map<LocalDate, ExecutionRecord> = emptyMap(),
    val lastEnvironmentCheck: String = "미조회",
    val sessionLabel: String = "사이트에서 로그인하세요",
    val siteRequest: Int = 0,
    val sitePath: String = "/",
    val mediaSupported: Boolean = false
)
sealed interface UiAction {
    data class SaveSettings(val settings: AppSettings, val expectedGeneration: Long? = null, val requestId: Long = 0): UiAction
    data class SaveAndArmRecurring(val snapshot: ActivationSnapshot, val acceptedPriceRisk: Boolean): UiAction
    data object StopAutomatic: UiAction
    data class SaveDate(val value: DateOverride): UiAction
    data class RestoreDate(val date: LocalDate): UiAction
    data class SaveCredentials(val userId: String, val password: String): UiAction
    data object DeleteCredentials: UiAction
    data class OpenEnvironment(val key: String): UiAction
    data object RefreshEnvironment: UiAction
    data object CheckBackgroundNow: UiAction
    data object CheckLogin: UiAction
    data class MockOrder(val date: LocalDate): UiAction
    data class OrderNow(val date: LocalDate, val acceptedPriceRisk: Boolean = false,
        val expectedGeneration: Long? = null, val expectedAccountGeneration: Long? = null): UiAction
    data class RefreshOrders(val date: LocalDate): UiAction
    data class ArmLive(val date: LocalDate, val recurring: Boolean, val acceptedPriceRisk: Boolean,
        val expectedGeneration: Long? = null, val expectedAccountGeneration: Long? = null): UiAction
    data class OpenSite(val path: String): UiAction
    data object ClearMessage: UiAction
    data object CompleteOnboarding: UiAction
    data object ExportBackup: UiAction
    data object ImportBackup: UiAction
    data object TestSound: UiAction
    data class TestNotification(val event: NotificationEvent, val preferences: NotificationPreferences): UiAction
    data class OpenNotificationSettings(val event: NotificationEvent): UiAction
    data object CheckUpdate: UiAction
    data object OpenRelease: UiAction
    data class ReviewBlock(val approved: Boolean): UiAction
}

data class ActivationSnapshot(
    val settings: AppSettings,
    val expectedGeneration: Long,
    val expectedAccountGeneration: Long,
    val accountLabel: String
)
