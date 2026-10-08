package com.fullmetalsonic.dosirak.platform

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.fullmetalsonic.dosirak.domain.ExecutionRecord
import com.fullmetalsonic.dosirak.domain.ExecutionStatus
import java.time.LocalDate

class OrderNotifier(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)

    init { ensureChannels() }

    fun foreground(date: LocalDate): Notification = builder(PROGRESS_CHANNEL, "$date 도시락 신청", "예약을 확인하고 있습니다.")
        .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).build()

    fun progress(date: LocalDate, message: String) {
        post(FOREGROUND_ID, builder(PROGRESS_CHANNEL, "$date 도시락 신청", message)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS).build())
    }

    fun clearProgress() {
        manager.cancel(FOREGROUND_ID)
    }

    fun preparing(date: LocalDate) {
        post(PREPARATION_ID, builder(PROGRESS_CHANNEL, "$date 예약 준비", "신청 시각 전에 로그인과 실행환경을 확인하세요.")
            .setAutoCancel(true).build())
    }

    fun result(record: ExecutionRecord) {
        val success = record.status == ExecutionStatus.COMPLETED
        val failure = record.status == ExecutionStatus.FAILED || record.status == ExecutionStatus.NEEDS_CHECK
        val title = "${record.date} ${record.status.label}"
        val content = if (success) "${record.quantity}개 주문완료를 확인했습니다." else record.message
        post(RESULT_ID + record.date.toEpochDay().toInt(), builder(if (failure) FAILURE_CHANNEL else RESULT_CHANNEL, title, content)
            .setCategory(if (failure) Notification.CATEGORY_ERROR else Notification.CATEGORY_STATUS)
            .setAutoCancel(true).apply {
                appIntent(record.date)?.let { setContentIntent(it); addAction(Notification.Action.Builder(null, "내역 보기", it).build()) }
            }.build())
    }

    fun blocked(message: String) {
        post(BLOCKED_ID, builder(FAILURE_CHANNEL, "예약 실행 확인 필요", message)
            .setCategory(Notification.CATEGORY_ERROR).setAutoCancel(true).build())
    }

    fun testSound() {
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        if (process.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return
        val sound = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)) ?: return
        synchronized(soundLock) {
            activeTest?.stop()
            activeTest = sound
            sound.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            runCatching { sound.play() }
            Handler(Looper.getMainLooper()).postDelayed({ synchronized(soundLock) {
                if (activeTest === sound) {
                    sound.stop()
                    activeTest = null
                }
            } }, 5_000L)
        }
    }

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

    private fun post(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching { manager.notify(id, notification) }
    }

    private fun ensureChannels() {
        val quiet = listOf(
            NotificationChannel(PROGRESS_CHANNEL, "예약 진행·준비", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(RESULT_CHANNEL, "주문완료", NotificationManager.IMPORTANCE_HIGH)
        )
        quiet.forEach { it.setSound(null, null); it.enableVibration(false) }
        val failure = NotificationChannel(FAILURE_CHANNEL, "예약 실패·확인 필요", NotificationManager.IMPORTANCE_HIGH)
        failure.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        failure.enableVibration(true)
        manager.createNotificationChannels(quiet + failure)
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
        private val soundLock = Any()
        private var activeTest: Ringtone? = null
    }
}
