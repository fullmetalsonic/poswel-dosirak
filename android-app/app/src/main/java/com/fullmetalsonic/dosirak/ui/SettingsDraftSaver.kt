package com.fullmetalsonic.dosirak.ui

import androidx.compose.runtime.saveable.mapSaver
import com.fullmetalsonic.dosirak.domain.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

internal val SettingsDraftSaver = mapSaver(
    save = { value: AppSettings ->
        mapOf("master" to value.masterEnabled, "auto" to value.dayAutoEnabled, "shift" to value.shiftType.name,
            "anchor" to value.patternAnchor.toString(), "confirmed" to value.patternConfirmed,
            "days" to value.weekdays.joinToString(",") { it.name }, "quantity" to value.defaultQuantity,
            "time" to value.orderTime.toString(), "limit" to value.limitEnabled,
            "p" to (value.unitLimit?.toString() ?: ""), "c" to (value.orderLimit?.toString() ?: ""),
            "retry" to value.retryCount, "interval" to value.retryIntervalSeconds,
            "preparation" to value.preparationAlert, "credentials" to value.useStoredCredentials,
            "media" to value.mediaAlarmEnabled, "volume" to value.mediaVolumePercent, "duration" to value.mediaDurationSeconds,
            "manufacturer" to value.manufacturerSettingsConfirmed, "scope" to value.liveScope.name,
            "test" to (value.liveTestDate?.toString() ?: ""), "risk" to value.displayPriceRiskAccepted, "generation" to value.generation,
            "accountGeneration" to value.accountGeneration, "verifiedLiveAccountGeneration" to (value.verifiedLiveAccountGeneration?.toString() ?: ""),
            "liveBlockedReason" to (value.liveBlockedReason ?: ""),
            "backgroundCheckEnabled" to value.backgroundCheckEnabled, "backgroundCheckMode" to value.backgroundCheckMode.name,
            "backgroundCheckTime" to value.backgroundCheckTime.toString()) + saveNotificationPreferences(value.notifications)
    },
    restore = { value ->
        AppSettings(masterEnabled = value["master"] as Boolean, dayAutoEnabled = value["auto"] as Boolean,
            shiftType = ShiftType.valueOf(value["shift"] as String), patternAnchor = LocalDate.parse(value["anchor"] as String),
            patternConfirmed = value["confirmed"] as Boolean, weekdays = (value["days"] as String).split(',').filter { it.isNotBlank() }.map { DayOfWeek.valueOf(it) }.toSet(),
            defaultQuantity = value["quantity"] as Int, orderTime = LocalTime.parse(value["time"] as String),
            limitEnabled = value["limit"] as Boolean, unitLimit = (value["p"] as String).toLongOrNull(), orderLimit = (value["c"] as String).toLongOrNull(),
            retryCount = value["retry"] as Int, retryIntervalSeconds = value["interval"] as Int,
            preparationAlert = value["preparation"] as Boolean, useStoredCredentials = value["credentials"] as Boolean,
            mediaAlarmEnabled = value["media"] as Boolean, mediaVolumePercent = value["volume"] as Int, mediaDurationSeconds = value["duration"] as Int,
            manufacturerSettingsConfirmed = value["manufacturer"] as Boolean, liveScope = LiveScope.valueOf(value["scope"] as String),
            liveTestDate = (value["test"] as String).takeIf { it.isNotBlank() }?.let(LocalDate::parse), displayPriceRiskAccepted = value["risk"] as Boolean,
            generation = value["generation"] as Long, accountGeneration = value["accountGeneration"] as Long,
            verifiedLiveAccountGeneration = (value["verifiedLiveAccountGeneration"] as String).toLongOrNull(),
            liveBlockedReason = (value["liveBlockedReason"] as String).takeIf { it.isNotBlank() },
            backgroundCheckEnabled = value["backgroundCheckEnabled"] as? Boolean ?: false,
            backgroundCheckMode = (value["backgroundCheckMode"] as? String)?.let(BackgroundCheckMode::valueOf) ?: BackgroundCheckMode.HOURLY,
            backgroundCheckTime = (value["backgroundCheckTime"] as? String)?.let(LocalTime::parse) ?: LocalTime.of(5, 50),
            notifications = restoreNotificationPreferences(value))
    }
)

private fun saveNotificationPreferences(preferences: NotificationPreferences?): Map<String, Any> {
    val fields = mutableMapOf<String, Any>("notificationsPresent" to (preferences != null))
    preferences?.let { value ->
        mapOf("preparation" to value.preparation, "success" to value.success, "failure" to value.failure).forEach { (event, options) ->
            fields["notification_${event}_enabled"] = options.enabled
            fields["notification_${event}_statusBar"] = options.statusBar
            fields["notification_${event}_popup"] = options.popup
            fields["notification_${event}_sound"] = options.sound
            fields["notification_${event}_vibration"] = options.vibration
        }
    }
    return fields
}

private fun restoreNotificationPreferences(fields: Map<String, Any?>): NotificationPreferences? {
    if (fields["notificationsPresent"] != true) return null
    fun options(event: String, fallback: AlertOptions): AlertOptions = AlertOptions(
        enabled = fields["notification_${event}_enabled"] as? Boolean ?: fallback.enabled,
        statusBar = fields["notification_${event}_statusBar"] as? Boolean ?: fallback.statusBar,
        popup = fields["notification_${event}_popup"] as? Boolean ?: fallback.popup,
        sound = fields["notification_${event}_sound"] as? Boolean ?: fallback.sound,
        vibration = fields["notification_${event}_vibration"] as? Boolean ?: fallback.vibration)
    val defaults = NotificationPreferences()
    return NotificationPreferences(options("preparation", defaults.preparation), options("success", defaults.success), options("failure", defaults.failure))
}
