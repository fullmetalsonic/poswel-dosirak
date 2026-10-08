package com.fullmetalsonic.dosirak.ui

import com.fullmetalsonic.dosirak.domain.*
import java.time.LocalDate

data class UiState(
    val settings: AppSettings = AppSettings(),
    val overrides: Map<LocalDate, DateOverride> = emptyMap(),
    val records: List<ExecutionRecord> = emptyList(),
    val environment: List<EnvironmentStatus> = emptyList(),
    val credentialsSaved: Boolean = false,
    val hasVerifiedLiveOrder: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val lastEnvironmentCheck: String = "미조회",
    val sessionLabel: String = "사이트에서 로그인하세요",
    val siteRequest: Int = 0,
    val sitePath: String = "/",
    val mediaSupported: Boolean = false
)
sealed interface UiAction {
    data class SaveSettings(val settings: AppSettings): UiAction
    data class SaveDate(val value: DateOverride): UiAction
    data class RestoreDate(val date: LocalDate): UiAction
    data class SaveCredentials(val userId: String, val password: String): UiAction
    data object DeleteCredentials: UiAction
    data class OpenEnvironment(val key: String): UiAction
    data object RefreshEnvironment: UiAction
    data object CheckLogin: UiAction
    data class MockOrder(val date: LocalDate): UiAction
    data class OrderNow(val date: LocalDate, val acceptedPriceRisk: Boolean = false): UiAction
    data class RefreshOrders(val date: LocalDate): UiAction
    data class ArmLive(val date: LocalDate, val recurring: Boolean, val acceptedPriceRisk: Boolean): UiAction
    data class OpenSite(val path: String): UiAction
    data object ClearMessage: UiAction
    data object ExportBackup: UiAction
    data object ImportBackup: UiAction
    data object TestSound: UiAction
    data class ReviewBlock(val approved: Boolean): UiAction
}
