package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.domain.*

internal enum class SettingsSection(val title: String, val icon: ImageVector) {
    ACCOUNT("계정", Icons.Outlined.Person), WORK("근무일", Icons.Outlined.CalendarMonth),
    ORDER("주문 설정", Icons.Outlined.Restaurant), ALERTS("알림", Icons.Outlined.Notifications),
    AUTOMATION("자동실행", Icons.Outlined.Schedule),
    PHONE("휴대폰 설정", Icons.Outlined.PhoneAndroid), BACKUP("백업", Icons.Outlined.Backup),
    APP("앱 정보", Icons.Outlined.Info)
}

internal fun sectionSettings(base: AppSettings, draft: AppSettings, section: SettingsSection): AppSettings = when (section) {
    SettingsSection.ACCOUNT -> base.copy(useStoredCredentials = draft.useStoredCredentials)
    SettingsSection.WORK -> base.copy(dayAutoEnabled = draft.dayAutoEnabled, shiftType = draft.shiftType,
        patternAnchor = draft.patternAnchor, patternConfirmed = draft.patternConfirmed, weekdays = draft.weekdays.toSet())
    SettingsSection.ORDER -> base.copy(defaultQuantity = draft.defaultQuantity, orderTime = draft.orderTime,
        limitEnabled = draft.limitEnabled, unitLimit = draft.unitLimit, orderLimit = draft.orderLimit,
        retryCount = draft.retryCount, retryIntervalSeconds = draft.retryIntervalSeconds)
    SettingsSection.ALERTS -> base.copy(notifications = draft.notifications, preparationAlert = draft.preparationAlert, mediaAlarmEnabled = draft.mediaAlarmEnabled,
        mediaVolumePercent = draft.mediaVolumePercent, mediaDurationSeconds = draft.mediaDurationSeconds)
    SettingsSection.AUTOMATION -> base.copy(backgroundCheckEnabled = draft.backgroundCheckEnabled,
        backgroundCheckMode = draft.backgroundCheckMode, backgroundCheckTime = draft.backgroundCheckTime)
    SettingsSection.PHONE -> base.copy(manufacturerSettingsConfirmed = draft.manufacturerSettingsConfirmed)
    SettingsSection.BACKUP -> base
    SettingsSection.APP -> base
}

@Composable
internal fun SettingsCategoryList(state: UiState, onSelect: (SettingsSection) -> Unit) {
    SettingsSection.entries.forEach { section ->
        val summary = when (section) {
            SettingsSection.ACCOUNT -> state.accountLabel.ifBlank { "저장된 계정 없음" }
            SettingsSection.WORK -> if (state.settings.dayAutoEnabled && !state.settings.patternConfirmed) "근무표 확인 필요"
                else if (state.settings.dayAutoEnabled) "${state.settings.shiftType.label} · 주간근무일 자동예약" else "달력에 저장한 날짜만 예약"
            SettingsSection.ORDER -> "${state.settings.defaultQuantity}개 · ${state.settings.orderTime.format(TimeFormat)}"
            SettingsSection.ALERTS -> state.settings.effectiveNotifications().let { preferences ->
                listOfNotNull(if (preferences.preparation.enabled) "준비" else null, if (preferences.success.enabled) "성공" else null,
                    if (preferences.failure.enabled) "실패" else null).joinToString(" · ").ifBlank { "모든 알림 꺼짐" }
            }
            SettingsSection.AUTOMATION -> if (!state.settings.backgroundCheckEnabled) "예약 점검 꺼짐"
                else if (state.settings.backgroundCheckMode == BackgroundCheckMode.HOURLY) "매시간"
                else "매일 ${state.settings.backgroundCheckTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}"
            SettingsSection.PHONE -> if (state.environment.any { it.blocking }) "확인이 필요한 항목 있음" else "알림 · 정확 알람 · 절전"
            SettingsSection.BACKUP -> "설정 내보내기 · 가져오기"
            SettingsSection.APP -> state.currentVersion
        }
        ListItem(headlineContent = { Text(section.title) }, supportingContent = { Text(summary) },
            leadingContent = { Icon(section.icon, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
            modifier = Modifier.fillMaxWidth().testTag("settings_category_${section.name}").clickable { onSelect(section) })
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

internal fun friendlyBlockReason(reason: String): String {
    val code = reason.substringBefore(':').trim()
    return when {
        code.contains("PRICE", true) || code.contains("AMOUNT", true) || code.contains("금액") || code.contains("가격") -> "주문내역의 금액을 확인해 주세요."
        code.contains("CONTRACT", true) || code.contains("SECURITY", true) -> "사이트 응답을 확인하지 못했습니다. 주문내역과 로그인을 확인해 주세요."
        code.contains("ACCOUNT", true) || code.contains("LOGIN", true) -> "저장 계정과 사이트 로그인을 확인해 주세요."
        else -> reason
    }
}
