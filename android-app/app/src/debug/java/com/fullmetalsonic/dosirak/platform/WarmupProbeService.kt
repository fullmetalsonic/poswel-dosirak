package com.fullmetalsonic.dosirak.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Debug-only native lifecycle probe. It never constructs the order runtime or a network client. */
class WarmupProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val token = intent?.getStringExtra(EXTRA_TOKEN)
        if (token.isNullOrBlank() || work?.isActive == true) {
            if (work?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        latest = Snapshot(token = token, startedElapsed = SystemClock.elapsedRealtime())
        try {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL, "사전대기 안전 시험", NotificationManager.IMPORTANCE_LOW)
                .apply { setSound(null, null); enableVibration(false) }
            manager.createNotificationChannel(channel)
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("사전대기 안전 시험").setContentText("구매·로그인·통신 없이 10초 대기합니다.")
                .setOngoing(true).setOnlyAlertOnce(true).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, notification)
            latest = latest?.copy(foregroundEntered = true)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:debug_warmup_probe")
                .apply { setReferenceCounted(false); acquire(WAKE_LOCK_MILLIS) }
            latest = latest?.copy(wakeLockAcquired = wakeLock?.isHeld == true)
            work = scope.launch {
                try {
                    val deadline = SystemClock.elapsedRealtime() + WAIT_MILLIS
                    while (SystemClock.elapsedRealtime() < deadline) delay(100L)
                } finally {
                    finishProbe()
                    stopSelfResult(latestStartId)
                }
            }
        } catch (error: RuntimeException) {
            latest = latest?.copy(error = error.javaClass.simpleName)
            finishProbe()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun finishProbe() {
        wakeLock?.let { if (it.isHeld) it.release() }
        latest = latest?.copy(finishedElapsed = SystemClock.elapsedRealtime(), wakeLockReleased = wakeLock?.isHeld != true)
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        latest = latest?.copy(error = "FGS_TIMEOUT")
        scope.cancel()
        finishProbe()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        finishProbe()
        latest = latest?.copy(destroyed = true)
        super.onDestroy()
    }

    data class Snapshot(
        val token: String,
        val startedElapsed: Long,
        val finishedElapsed: Long = 0L,
        val foregroundEntered: Boolean = false,
        val wakeLockAcquired: Boolean = false,
        val wakeLockReleased: Boolean = false,
        val destroyed: Boolean = false,
        val error: String? = null
    )

    companion object {
        const val EXTRA_TOKEN = "debug_probe_token"
        const val WAIT_MILLIS = 10_000L
        private const val WAKE_LOCK_MILLIS = 20_000L
        private const val CHANNEL = "debug_warmup_probe"
        private const val NOTIFICATION_ID = 22_337
        @Volatile var latest: Snapshot? = null
            private set
    }
}
