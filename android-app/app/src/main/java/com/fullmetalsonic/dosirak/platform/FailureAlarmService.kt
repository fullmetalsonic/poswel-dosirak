package com.fullmetalsonic.dosirak.platform

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.fullmetalsonic.dosirak.R
import com.fullmetalsonic.dosirak.diagnostics.*
import com.fullmetalsonic.dosirak.domain.*

enum class FailureAudioStart { REQUESTED, PLATFORM_RESTRICTED, DENIED }

/** Independent, bounded playback. It never starts or holds the purchase service. */
class FailureAlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var mode = FailureSoundMode.ALARM
    private var finished = false
    private val timeout = Runnable { finishAudio(DiagnosticCode.AUDIO_STOPPED) }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent == null) {
            finishAudio(DiagnosticCode.AUDIO_STOPPED)
            return START_NOT_STICKY
        }
        val request = readRequest(intent)
        if (request == null || !OrderNotifier(this).notificationsAllowed()) {
            finishAudio(DiagnosticCode.AUDIO_CHANNEL_BLOCKED)
            return START_NOT_STICKY
        }
        mode = request.mode
        if (audio.getStreamVolume(if (mode == FailureSoundMode.MEDIA) AudioManager.STREAM_MUSIC else AudioManager.STREAM_ALARM) == 0)
            record(this, mode, DiagnosticCode.AUDIO_STREAM_MUTED, DiagnosticResult.BLOCKED)
        if (request.volumePercent == 0) record(this, mode, DiagnosticCode.AUDIO_PLAYER_GAIN_ZERO, DiagnosticResult.BLOCKED)
        val manager = getSystemService(NotificationManager::class.java)
        val sourceChannel = intent.getStringExtra(EXTRA_CHANNEL) ?: return stopWithoutPlayback()
        if (manager.getNotificationChannel(sourceChannel)?.importance.let { it == null || it == NotificationManager.IMPORTANCE_NONE } ||
            manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            finishAudio(DiagnosticCode.AUDIO_CHANNEL_BLOCKED)
            return START_NOT_STICKY
        }
        if (manager.getNotificationChannel(sourceChannel)?.sound != null || manager.getNotificationChannel(CHANNEL)?.sound != null) {
            finishAudio(DiagnosticCode.AUDIO_CHANNEL_HAS_SOUND)
            return START_NOT_STICKY
        }
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "실패 소리 재생", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false)
            })
        }
        val stop = PendingIntent.getService(this, 0, Intent(this, FailureAlarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("실패 경보 재생 중").setContentText("${request.mode.label} · ${request.durationSeconds}초")
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "소리 정지", stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(ID, notification)
        } catch (_: Exception) {
            finishAudio(DiagnosticCode.AUDIO_FGS_DENIED)
            return START_NOT_STICKY
        }
        record(this, mode, DiagnosticCode.AUDIO_SERVICE_STARTED, DiagnosticResult.STARTED)
        val event = intent.getIntExtra(EXTRA_EVENT, -1)
        val testing = intent.getBooleanExtra(EXTRA_TEST, false)
        if (!testing && duplicate(event, SystemClock.elapsedRealtime())) {
            record(this, mode, DiagnosticCode.AUDIO_DUPLICATE, DiagnosticResult.SKIPPED)
            if (player == null) finishAudio(DiagnosticCode.AUDIO_DUPLICATE)
            return START_NOT_STICKY
        }
        releasePlayer()
        finished = false
        val attributes = AudioAttributes.Builder().setUsage(if (mode == FailureSoundMode.MEDIA)
            AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        var requestFocus: AudioFocusRequest? = null
        requestFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 && focus === requestFocus) finishAudio(DiagnosticCode.AUDIO_FOCUS_LOST)
            }, handler).build()
        focus = requestFocus
        if (runCatching { audio.requestAudioFocus(requireNotNull(requestFocus)) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            finishAudio(DiagnosticCode.AUDIO_FOCUS_DENIED)
            return START_NOT_STICKY
        }
        if (finished) return START_NOT_STICKY
        try {
            val newPlayer = MediaPlayer()
            player = newPlayer
            newPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            newPlayer.setAudioAttributes(attributes)
            resources.openRawResourceFd(R.raw.failure_alarm).use { sound ->
                newPlayer.setDataSource(sound.fileDescriptor, sound.startOffset, sound.length)
            }
            newPlayer.isLooping = true
            val gain = request.volumePercent / 100f
            newPlayer.setVolume(gain, gain)
            newPlayer.setOnErrorListener { failed, _, _ ->
                if (player === failed) finishAudio(DiagnosticCode.AUDIO_PLAY_FAILED)
                true
            }
            newPlayer.setOnPreparedListener {
                if (player === it && !finished) {
                    try {
                        it.start()
                        record(this, mode, DiagnosticCode.AUDIO_PLAY_STARTED, DiagnosticResult.SUCCEEDED)
                    } catch (_: Exception) { finishAudio(DiagnosticCode.AUDIO_PLAY_FAILED) }
                }
            }
            newPlayer.prepareAsync()
            handler.postDelayed(timeout, request.durationSeconds * 1000L)
        } catch (_: Exception) { finishAudio(DiagnosticCode.AUDIO_PLAY_FAILED) }
        return START_NOT_STICKY
    }

    private fun stopWithoutPlayback(): Int { stopSelf(); return START_NOT_STICKY }

    private fun releasePlayer() {
        handler.removeCallbacks(timeout)
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        val previousFocus = focus
        focus = null
        previousFocus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
    }

    private fun finishAudio(code: DiagnosticCode) {
        if (!finished) {
            finished = true
            releasePlayer()
            record(this, mode, code, if (code in setOf(DiagnosticCode.AUDIO_STOPPED, DiagnosticCode.AUDIO_FOCUS_LOST,
                DiagnosticCode.AUDIO_DUPLICATE)) DiagnosticResult.CANCELLED else DiagnosticResult.BLOCKED)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { finishAudio(DiagnosticCode.AUDIO_STOPPED); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "failure_audio_playback"
        const val ACTION_STOP = "com.fullmetalsonic.dosirak.STOP_FAILURE_AUDIO"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_VOLUME = "volume"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_CHANNEL = "channel"
        private const val EXTRA_EVENT = "event"
        private const val EXTRA_TEST = "test"
        private const val ID = 106
        private var lastEvent = Int.MIN_VALUE
        private var lastEventAt = Long.MIN_VALUE

        @Synchronized internal fun duplicate(event: Int, now: Long): Boolean {
            if (event == lastEvent && now - lastEventAt in 0..60_000) return true
            lastEvent = event; lastEventAt = now
            return false
        }

        internal fun mediaRestricted(sdk: Int, foreground: Boolean) = sdk >= 37 && !foreground

        fun start(context: Context, request: FailureSoundRequest, channel: String, eventId: Int, testing: Boolean = false): FailureAudioStart {
            val safe = request.normalized()
            val memory = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(memory)
            if (safe.mode == FailureSoundMode.MEDIA && mediaRestricted(Build.VERSION.SDK_INT,
                    memory.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)) {
                record(context, safe.mode, DiagnosticCode.AUDIO_PLATFORM_RESTRICTED, DiagnosticResult.BLOCKED)
                return FailureAudioStart.PLATFORM_RESTRICTED
            }
            if (safe.mode == FailureSoundMode.NOTIFICATION) return FailureAudioStart.DENIED
            return try {
                context.startForegroundService(Intent(context, FailureAlarmService::class.java)
                    .putExtra(EXTRA_MODE, safe.mode.name).putExtra(EXTRA_VOLUME, safe.volumePercent)
                    .putExtra(EXTRA_DURATION, safe.durationSeconds).putExtra(EXTRA_CHANNEL, channel)
                    .putExtra(EXTRA_EVENT, eventId).putExtra(EXTRA_TEST, testing))
                FailureAudioStart.REQUESTED
            } catch (_: Exception) {
                record(context, safe.mode, DiagnosticCode.AUDIO_FGS_DENIED, DiagnosticResult.BLOCKED)
                FailureAudioStart.DENIED
            }
        }

        fun stop(context: Context) { context.stopService(Intent(context, FailureAlarmService::class.java)) }

        private fun readRequest(intent: Intent): FailureSoundRequest? = runCatching {
            FailureSoundRequest(FailureSoundMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: ""),
                intent.getIntExtra(EXTRA_VOLUME, 70), intent.getIntExtra(EXTRA_DURATION, 15)).normalized()
        }.getOrNull()?.takeUnless { it.mode == FailureSoundMode.NOTIFICATION }

        private fun record(context: Context, mode: FailureSoundMode, code: DiagnosticCode, result: DiagnosticResult) {
            runCatching { AndroidDiagnosticProvider.get(context).safeRecord(DiagnosticEvent(
                if (mode == FailureSoundMode.MEDIA) DiagnosticEventType.AUDIO_MEDIA else DiagnosticEventType.AUDIO_ALARM,
                DiagnosticStage.NOTIFICATION, result, code, notificationEvent = DiagnosticNotificationEvent.FAILURE)) }
        }
    }
}
