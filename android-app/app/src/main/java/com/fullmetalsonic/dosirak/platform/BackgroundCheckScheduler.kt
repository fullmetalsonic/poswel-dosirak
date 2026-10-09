package com.fullmetalsonic.dosirak.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.os.UserManager
import com.fullmetalsonic.dosirak.data.AppStore
import com.fullmetalsonic.dosirak.domain.BackgroundCheckMode
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

data class BackgroundCheckRegistration(val registered: Boolean, val message: String, val nextCheckEpochMillis: Long? = null)
data class BackgroundCheckStatus(val lastCheckEpochMillis: Long?, val nextCheckEpochMillis: Long?, val summary: String)

class BackgroundCheckScheduler internal constructor(
    context: Context,
    private val store: AppStore,
    private val preferencesName: String,
    private val alarm: BackgroundCheckAlarmBackend,
    private val clock: () -> BackgroundCheckNow
) {
    constructor(context: Context, store: AppStore) : this(context.applicationContext, store,
        "background_check_metadata", AndroidBackgroundCheckAlarm(context.applicationContext),
        { BackgroundCheckNow(System.currentTimeMillis(), SystemClock.elapsedRealtime()) })

    private val context = context.applicationContext
    private fun unlocked() = context.getSystemService(UserManager::class.java).isUserUnlocked
    private fun metadata(): SharedPreferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun reschedule(force: Boolean = false): BackgroundCheckRegistration {
        if (!unlocked()) return BackgroundCheckRegistration(false, "첫 잠금 해제 후 백그라운드 점검을 등록합니다.")
        return synchronized(store) {
            val settings = store.loadSettings()
            synchronized(METADATA_LOCK) metadataLock@ {
                val preferences = metadata()
                if (!settings.backgroundCheckEnabled) {
                    cancelLocked(preferences)
                    return@metadataLock BackgroundCheckRegistration(false, "백그라운드 점검이 꺼져 있습니다.")
                }
                val now = clock()
                val sameConfig = preferences.getString(MODE, null) == settings.backgroundCheckMode.name &&
                    preferences.getInt(TIME, -1) == settings.backgroundCheckTime.toSecondOfDay()
                val pending = preferences.getString(TOKEN, null)
                val future = when (settings.backgroundCheckMode) {
                    BackgroundCheckMode.HOURLY -> preferences.getLong(CREATED_ELAPSED, -1L) <= now.elapsedMillis &&
                        preferences.getLong(NEXT_ELAPSED, 0L) > now.elapsedMillis
                    BackgroundCheckMode.DAILY -> preferences.getLong(NEXT_EPOCH, 0L) > now.epochMillis
                }
                if (!force && sameConfig && pending != null && future) {
                    return@metadataLock BackgroundCheckRegistration(true, "다음 백그라운드 점검이 등록되어 있습니다.", nextEpoch(preferences, now))
                }
                cancelLocked(preferences)
                val next = BackgroundCheckTiming.next(settings.backgroundCheckMode, settings.backgroundCheckTime, now)
                val token = UUID.randomUUID().toString()
                if (!preferences.edit().putString(TOKEN, token).putString(MODE, settings.backgroundCheckMode.name)
                        .putInt(TIME, settings.backgroundCheckTime.toSecondOfDay()).putLong(NEXT_EPOCH, next.epochMillis)
                        .putLong(NEXT_ELAPSED, next.elapsedMillis).putLong(CREATED_ELAPSED, now.elapsedMillis).commit()) {
                    return@metadataLock BackgroundCheckRegistration(false, "점검 알람 정보를 저장하지 못했습니다.")
                }
                try {
                    alarm.schedule(settings.backgroundCheckMode, next, token)
                    BackgroundCheckRegistration(true, "백그라운드 점검 알람을 등록했습니다.", next.epochMillis)
                } catch (_: RuntimeException) {
                    cancelLocked(preferences)
                    BackgroundCheckRegistration(false, "Android가 점검 알람 등록을 거부했습니다.")
                }
            }
        }
    }

    fun cancel() = synchronized(METADATA_LOCK) { cancelLocked(metadata()) }

    fun status(): BackgroundCheckStatus {
        if (!unlocked()) return BackgroundCheckStatus(null, null, "첫 잠금 해제 후 점검 상태를 확인합니다.")
        return synchronized(METADATA_LOCK) {
            val preferences = metadata()
            BackgroundCheckStatus(preferences.getLong(LAST_CHECK, 0L).takeIf { it > 0L },
                nextEpoch(preferences, clock()).takeIf { preferences.getString(TOKEN, null) != null && it > 0L },
                preferences.getString(LAST_SUMMARY, null) ?: "아직 백그라운드 점검을 실행하지 않았습니다.")
        }
    }

    // Manual inspection is independent of periodic registration and its pending token.
    fun checkNow(checkReservation: () -> RegistrationResult): RegistrationResult {
        if (!unlocked()) return RegistrationResult(RegistrationCode.BLOCKED, "첫 잠금 해제 후 예약 등록을 점검할 수 있습니다.")
        val code = try { checkReservation().code } catch (_: Exception) { RegistrationCode.FAILED }
        val summary = summaryFor(code)
        val saved = synchronized(METADATA_LOCK) {
            metadata().edit().putLong(LAST_CHECK, clock().epochMillis).putString(LAST_SUMMARY, summary).commit()
        }
        return if (saved) RegistrationResult(code, summary)
        else RegistrationResult(RegistrationCode.FAILED, "점검 기록을 저장하지 못했습니다.")
    }

    internal fun dispatch(token: String?, checkReservation: () -> RegistrationResult): Boolean {
        if (!unlocked()) return false
        val consumed = synchronized(store) {
            val settings = store.loadSettings()
            synchronized(METADATA_LOCK) metadataLock@ {
                val preferences = metadata()
                if (!settings.backgroundCheckEnabled) {
                    cancelLocked(preferences)
                    return@metadataLock false
                }
                val now = clock()
                val mode = readMode(preferences)
                if (mode != settings.backgroundCheckMode || preferences.getInt(TIME, -1) != settings.backgroundCheckTime.toSecondOfDay() ||
                    !BackgroundCheckDispatchGuard.isDue(preferences.getString(TOKEN, null), token, mode,
                        preferences.getLong(NEXT_EPOCH, 0L), preferences.getLong(NEXT_ELAPSED, 0L), now)) return@metadataLock false
                preferences.edit().remove(TOKEN).remove(NEXT_EPOCH).remove(NEXT_ELAPSED).putString(PROCESSING, token).commit()
            }
        }
        if (!consumed) return false
        try {
            // Do not hold the store lock while acquiring ReservationScheduler's lock.
            if (!unlocked() || !store.loadSettings().backgroundCheckEnabled || !isProcessing(token)) return false
            val result = try { checkReservation().code } catch (_: Exception) { RegistrationCode.FAILED }
            synchronized(store) {
                if (store.loadSettings().backgroundCheckEnabled) synchronized(METADATA_LOCK) {
                    val preferences = metadata()
                    if (preferences.getString(PROCESSING, null) == token) preferences.edit()
                        .remove(PROCESSING).putLong(LAST_CHECK, clock().epochMillis)
                        .putString(LAST_SUMMARY, summaryFor(result)).commit()
                }
            }
            return true
        } finally {
            reschedule()
        }
    }

    private fun isProcessing(token: String?) = synchronized(METADATA_LOCK) {
        token != null && metadata().getString(PROCESSING, null) == token
    }

    private fun cancelLocked(preferences: SharedPreferences) {
        alarm.cancel()
        preferences.edit().remove(TOKEN).remove(PROCESSING).remove(NEXT_EPOCH).remove(NEXT_ELAPSED)
            .remove(CREATED_ELAPSED).remove(MODE).remove(TIME).commit()
    }

    private fun nextEpoch(preferences: SharedPreferences, now: BackgroundCheckNow): Long =
        if (readMode(preferences) == BackgroundCheckMode.HOURLY)
            now.epochMillis + (preferences.getLong(NEXT_ELAPSED, 0L) - now.elapsedMillis)
        else preferences.getLong(NEXT_EPOCH, 0L)

    private fun readMode(preferences: SharedPreferences): BackgroundCheckMode? =
        runCatching { BackgroundCheckMode.valueOf(preferences.getString(MODE, null) ?: "") }.getOrNull()

    companion object {
        const val ACTION_CHECK = "com.fullmetalsonic.dosirak.BACKGROUND_CHECK"
        const val EXTRA_TOKEN = "background_check_token"
        private val METADATA_LOCK = Any()
        private const val TOKEN = "pending_token"
        private const val PROCESSING = "processing_token"
        private const val NEXT_EPOCH = "next_check_epoch"
        private const val NEXT_ELAPSED = "next_check_elapsed"
        private const val CREATED_ELAPSED = "created_elapsed"
        private const val MODE = "check_mode"
        private const val TIME = "check_time_seconds"
        private const val LAST_CHECK = "last_check_epoch"
        private const val LAST_SUMMARY = "last_check_summary"

        private fun summaryFor(code: RegistrationCode): String = when (code) {
            RegistrationCode.REGISTERED -> "점검 완료 · 예약 알람 등록됨"
            RegistrationCode.IN_FLIGHT -> "점검 완료 · 신청 준비/처리 중"
            RegistrationCode.STOPPED -> "점검 완료 · 자동주문 비활성"
            RegistrationCode.NO_FUTURE_PLAN -> "점검 완료 · 미래 예약 없음"
            RegistrationCode.BLOCKED -> "점검 완료 · 예약 실행 차단 유지"
            RegistrationCode.FAILED -> "점검 실패 · 예약 등록 상태를 확인하세요."
        }
    }
}

internal data class BackgroundCheckNow(val epochMillis: Long, val elapsedMillis: Long)

internal object BackgroundCheckTiming {
    private val zone = ZoneId.of("Asia/Seoul")
    fun next(mode: BackgroundCheckMode, time: LocalTime, now: BackgroundCheckNow): BackgroundCheckNow = when (mode) {
        BackgroundCheckMode.HOURLY -> BackgroundCheckNow(now.epochMillis + 3_600_000L, now.elapsedMillis + 3_600_000L)
        BackgroundCheckMode.DAILY -> {
            val current = Instant.ofEpochMilli(now.epochMillis).atZone(zone)
            var next = current.toLocalDate().atTime(time).atZone(zone)
            if (!next.toInstant().isAfter(current.toInstant())) next = next.plusDays(1)
            BackgroundCheckNow(next.toInstant().toEpochMilli(), 0L)
        }
    }
}

internal object BackgroundCheckDispatchGuard {
    fun isDue(expected: String?, received: String?, mode: BackgroundCheckMode?, epoch: Long, elapsed: Long, now: BackgroundCheckNow): Boolean =
        !expected.isNullOrEmpty() && expected == received && when (mode) {
            BackgroundCheckMode.HOURLY -> elapsed > 0L && elapsed <= now.elapsedMillis
            BackgroundCheckMode.DAILY -> epoch > 0L && epoch <= now.epochMillis
            null -> false
        }
}

internal interface BackgroundCheckAlarmBackend {
    fun schedule(mode: BackgroundCheckMode, next: BackgroundCheckNow, token: String)
    fun cancel()
}

private class AndroidBackgroundCheckAlarm(private val context: Context) : BackgroundCheckAlarmBackend {
    override fun schedule(mode: BackgroundCheckMode, next: BackgroundCheckNow, token: String) {
        context.getSystemService(AlarmManager::class.java).set(
            if (mode == BackgroundCheckMode.HOURLY) AlarmManager.ELAPSED_REALTIME_WAKEUP else AlarmManager.RTC_WAKEUP,
            if (mode == BackgroundCheckMode.HOURLY) next.elapsedMillis else next.epochMillis, pending(token))
    }
    override fun cancel() { context.getSystemService(AlarmManager::class.java).cancel(pending("")) }
    private fun pending(token: String): PendingIntent = PendingIntent.getBroadcast(context, 3,
        Intent(context, BackgroundCheckReceiver::class.java).setAction(BackgroundCheckScheduler.ACTION_CHECK)
            .putExtra(BackgroundCheckScheduler.EXTRA_TOKEN, token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
