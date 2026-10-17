package com.fullmetalsonic.dosirak.domain

enum class NotificationEvent(val label: String) {
    PREPARATION("준비"), SUCCESS("주문완료"), FAILURE("실패·확인 필요")
}

enum class FailureSoundMode(val label: String) {
    NOTIFICATION("알림 볼륨"), ALARM("알람 볼륨"), MEDIA("미디어 볼륨")
}

fun AppSettings.effectiveFailureSoundMode(): FailureSoundMode = when (failureSoundMode) {
    FailureSoundMode.NOTIFICATION -> FailureSoundMode.NOTIFICATION
    FailureSoundMode.ALARM, FailureSoundMode.MEDIA -> FailureSoundMode.ALARM
    null -> FailureSoundMode.ALARM
}

fun AppSettings.normalizedFailureSound(): AppSettings = copy(failureSoundMode = effectiveFailureSoundMode(), mediaAlarmEnabled = false)

data class FailureSoundRequest(val mode: FailureSoundMode, val volumePercent: Int = 70, val durationSeconds: Int = 15) {
    fun normalized() = copy(mode = if (mode == FailureSoundMode.MEDIA) FailureSoundMode.ALARM else mode,
        volumePercent = volumePercent.coerceIn(0, 100), durationSeconds = durationSeconds.coerceIn(1, 60))
}

fun AppSettings.failureSoundRequest() = FailureSoundRequest(effectiveFailureSoundMode(), mediaVolumePercent, mediaDurationSeconds).normalized()

data class AlertOptions(
    val enabled: Boolean = true,
    val statusBar: Boolean = true,
    val popup: Boolean = false,
    val sound: Boolean = false,
    val vibration: Boolean = false
) {
    fun normalized(): AlertOptions {
        val anyMethod = statusBar || popup || sound || vibration
        return copy(enabled = enabled && anyMethod, statusBar = anyMethod)
    }

    fun profile(): Int = (if (popup) 4 else 0) + (if (sound) 2 else 0) + (if (vibration) 1 else 0)
}

data class NotificationPreferences(
    val preparation: AlertOptions = AlertOptions(),
    val success: AlertOptions = AlertOptions(),
    val failure: AlertOptions = AlertOptions(popup = true, sound = true, vibration = true)
) {
    fun normalized(): NotificationPreferences = copy(preparation = preparation.normalized(),
        success = success.normalized(), failure = failure.normalized())

    fun options(event: NotificationEvent): AlertOptions = when (event) {
        NotificationEvent.PREPARATION -> preparation
        NotificationEvent.SUCCESS -> success
        NotificationEvent.FAILURE -> failure
    }.normalized()
}

fun AppSettings.effectiveNotifications(): NotificationPreferences =
    (notifications ?: NotificationPreferences(preparation = AlertOptions(enabled = preparationAlert))).normalized()
