package com.fullmetalsonic.dosirak.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import com.fullmetalsonic.dosirak.data.AppStore
import com.fullmetalsonic.dosirak.domain.*
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

enum class NotificationTestCode { SENT, EVENT_DISABLED, APP_DISABLED, CHANNEL_BLOCKED, FAILED }

interface OrderNotificationDelivery {
    fun channel(id: String): NotificationChannel?
    fun create(channel: NotificationChannel)
    fun enabled(): Boolean
    fun notify(id: Int, notification: Notification)
    fun cancel(id: Int)
}

class OrderNotifier(context: Context,
    private val settingsLoader: () -> AppSettings = { AppStore(context).use { it.loadSettings() } },
    delivery: OrderNotificationDelivery? = null
) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)
    private val delivery = delivery ?: object : OrderNotificationDelivery {
        override fun channel(id: String) = manager.getNotificationChannel(id)
        override fun create(channel: NotificationChannel) = manager.createNotificationChannel(channel)
        override fun enabled() = manager.areNotificationsEnabled()
        override fun notify(id: Int, notification: Notification) = manager.notify(id, notification)
        override fun cancel(id: Int) = manager.cancel(id)
    }
    private val channelHistory = this.context.getSharedPreferences("notification_channel_profiles", Context.MODE_PRIVATE)
    private val lastBlockedChannels = ConcurrentHashMap<NotificationEvent, String>()

    init { ensureProgressChannel() }

    fun foreground(date: LocalDate): Notification = builder(PROGRESS_CHANNEL, "$date 도시락 신청", "주문 준비 · 사이트 신청 시각을 확인합니다.")
        .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).build()

    fun progress(date: LocalDate, message: String) {
        post(FOREGROUND_ID, builder(PROGRESS_CHANNEL, "$date 도시락 신청", message)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS).build())
    }

    fun clearProgress() {
        delivery.cancel(FOREGROUND_ID)
    }

    fun preparing(date: LocalDate) {
        deliverEvent(NotificationEvent.PREPARATION, PREPARATION_ID, "$date 예약 준비",
            "신청 시각 전에 로그인과 실행환경을 확인하세요.", date)
    }

    fun result(record: ExecutionRecord) {
        val success = record.status == ExecutionStatus.COMPLETED
        val failure = record.status == ExecutionStatus.FAILED || record.status == ExecutionStatus.NEEDS_CHECK
        if (!success && !failure) return
        val title = "${record.date} ${record.status.label}"
        val content = if (success) "${record.quantity}개 주문완료를 확인했습니다." else record.message
        deliverEvent(if (success) NotificationEvent.SUCCESS else NotificationEvent.FAILURE,
            RESULT_ID + record.date.toEpochDay().toInt(), title, content, record.date)
    }

    fun blocked(message: String) {
        deliverEvent(NotificationEvent.FAILURE, BLOCKED_ID, "예약 실행 확인 필요", message)
    }

    fun test(event: NotificationEvent, preferences: NotificationPreferences): NotificationTestCode =
        deliverEvent(event, TEST_ID + event.ordinal, "${event.label} 알림 시험", "알림 설정 시험입니다. 주문을 실행하지 않습니다.",
            preferences = preferences.normalized())

    fun notificationsAllowed(): Boolean = runCatching { delivery.enabled() && permissionGranted() }.getOrDefault(false)

    fun notificationSettingsIntent(event: NotificationEvent): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, blockingChannel(event) ?: lastBlockedChannels[event]?.takeIf {
            delivery.channel(it)?.importance == NotificationManager.IMPORTANCE_NONE
        } ?: channelHistory.getString(event.name, null) ?: legacyChannel(event))

    private fun deliverEvent(event: NotificationEvent, id: Int, title: String, message: String, date: LocalDate? = null,
        preferences: NotificationPreferences? = null): NotificationTestCode = try {
        val effective = preferences ?: runCatching { settingsLoader().effectiveNotifications() }.getOrNull()
        if (effective == null) NotificationTestCode.FAILED else {
        val options = effective.options(event)
        val channelId = "reservation_${event.name.lowercase()}_p${options.profile()}"
        val blockedChannel = blockingChannel(event) ?: channelId.takeIf {
            delivery.channel(it)?.importance == NotificationManager.IMPORTANCE_NONE
        }
        when {
            !options.enabled || !options.statusBar -> NotificationTestCode.EVENT_DISABLED
            !notificationsAllowed() -> NotificationTestCode.APP_DISABLED
            blockedChannel != null -> {
                lastBlockedChannels[event] = blockedChannel
                NotificationTestCode.CHANNEL_BLOCKED
            }
            else -> {
                ensureEventChannel(event, options, channelId)
                // Keep profile history before posting so an app-option change cannot bypass an OS block.
                if (!channelHistory.edit().putString(event.name, channelId).commit()) NotificationTestCode.FAILED else {
                    post(id, builder(channelId, title, message).setAutoCancel(true)
                        .setCategory(if (event == NotificationEvent.FAILURE) Notification.CATEGORY_ERROR else Notification.CATEGORY_STATUS)
                        .apply { if (date != null) appIntent(date)?.let {
                            setContentIntent(it); addAction(Notification.Action.Builder(null, "내역 보기", it).build())
                        } }.build())
                }
            }
        }
        }
    } catch (_: Exception) { NotificationTestCode.FAILED }

    private fun legacyChannel(event: NotificationEvent): String = when (event) {
        NotificationEvent.PREPARATION -> PROGRESS_CHANNEL
        NotificationEvent.SUCCESS -> RESULT_CHANNEL
        NotificationEvent.FAILURE -> FAILURE_CHANNEL
    }

    private fun blockingChannel(event: NotificationEvent): String? =
        listOfNotNull(legacyChannel(event), channelHistory.getString(event.name, null), lastBlockedChannels[event]).firstOrNull {
            delivery.channel(it)?.importance == NotificationManager.IMPORTANCE_NONE
        }

    private fun ensureEventChannel(event: NotificationEvent, options: AlertOptions, id: String) {
        if (delivery.channel(id) != null) return
        val importance = when {
            options.popup -> NotificationManager.IMPORTANCE_HIGH
            options.sound || options.vibration -> NotificationManager.IMPORTANCE_DEFAULT
            else -> NotificationManager.IMPORTANCE_LOW
        }
        val channel = NotificationChannel(id, "${event.label} · 알림 ${options.profile()}", importance)
        channel.setSound(if (options.sound) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else null,
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        channel.enableVibration(options.vibration)
        delivery.create(channel)
    }

    fun testSound(): NotificationTestCode =
        test(NotificationEvent.FAILURE, NotificationPreferences(failure = AlertOptions(sound = true)))

    private fun builder(channel: String, title: String, message: String): Notification.Builder =
        Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .apply { appIntent()?.let { setContentIntent(it) } }

    private fun appIntent(date: LocalDate? = null): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (date != null) intent.putExtra(EXTRA_HISTORY_DATE, date.toString())
        return PendingIntent.getActivity(context, date?.toEpochDay()?.toInt() ?: 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun post(id: Int, notification: Notification): NotificationTestCode =
        if (!notificationsAllowed()) NotificationTestCode.APP_DISABLED else
            runCatching { delivery.notify(id, notification); NotificationTestCode.SENT }.getOrDefault(NotificationTestCode.FAILED)

    private fun permissionGranted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureProgressChannel() {
        if (delivery.channel(PROGRESS_CHANNEL) != null) return
        val channel = NotificationChannel(PROGRESS_CHANNEL, "예약 진행", NotificationManager.IMPORTANCE_LOW)
        channel.setSound(null, null)
        channel.enableVibration(false)
        delivery.create(channel)
    }

    companion object {
        const val PROGRESS_CHANNEL = "reservation_progress"
        const val RESULT_CHANNEL = "reservation_result"
        const val FAILURE_CHANNEL = "reservation_failure"
        const val FOREGROUND_ID = 100
        const val EXTRA_HISTORY_DATE = "notification_history_date"
        private const val PREPARATION_ID = 101
        private const val BLOCKED_ID = 102
        private const val RESULT_ID = 10_000
        private const val TEST_ID = 103
    }
}
