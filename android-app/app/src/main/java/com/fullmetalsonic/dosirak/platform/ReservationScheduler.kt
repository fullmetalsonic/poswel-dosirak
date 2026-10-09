package com.fullmetalsonic.dosirak.platform

import android.app.AlarmManager
import android.app.Activity
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.fullmetalsonic.dosirak.data.AppStore
import com.fullmetalsonic.dosirak.domain.LiveScope
import com.fullmetalsonic.dosirak.domain.ScheduleCalculator
import com.fullmetalsonic.dosirak.domain.effectiveNotifications
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReservationScheduler internal constructor(context: Context, private val store: AppStore, private val clock: Clock,
    private val pendingAlarm: (Context, Boolean, String) -> PendingIntent,
    private val dispatchService: (Context, Intent) -> Unit = { ctx, intent -> ctx.startForegroundService(intent); Unit }) {
    constructor(context: Context, store: AppStore, clock: Clock = Clock.system(ZONE)) :
        this(context, store, clock, { ctx, preparation, token -> alarmIntent(ctx, preparation, token) })
    private val context = context.applicationContext

    @Synchronized fun reschedule(preserveDue: Boolean = true): RegistrationResult {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
            restoreLocked()
            return RegistrationResult(RegistrationCode.BLOCKED, "재부팅 후 첫 잠금 해제가 필요합니다. 자동주문 설정은 유지됩니다.")
        }
        val settings = store.loadSettings()
        val wallNow = System.currentTimeMillis()
        val now = clock.instant()
        val plans = ScheduleCalculator.upcomingPlans(settings, store.loadOverrides(), now.atZone(ZONE).toLocalDate(), 93)
        val records = store.loadRecords()
        return synchronized(METADATA_LOCK) {
            val preferences = metadata(context)
            val flightToken = preferences.getString(IN_FLIGHT_TOKEN, null)
            val flightTarget = preferences.getLong(IN_FLIGHT_TARGET, 0L)
            val flightGeneration = preferences.getLong(IN_FLIGHT_GENERATION, -1L)
            val flightEligible = settings.masterEnabled && settings.liveScope != LiveScope.NONE &&
                settings.displayPriceRiskAccepted && settings.liveBlockedReason == null &&
                preferences.getLong(IN_FLIGHT_ACCOUNT_GENERATION, -1L) == settings.accountGeneration && plans.any {
                    it.date.atTime(it.time).atZone(ZONE).toInstant().toEpochMilli() == flightTarget &&
                        (settings.liveScope != LiveScope.SINGLE_DATE || it.date == settings.liveTestDate)
                }
            val flightKey = AlarmDispatchGuard.key(Instant.ofEpochMilli(flightTarget).atZone(ZONE).toLocalDate(), flightGeneration)
            val elapsedNow = SystemClock.elapsedRealtime()
            val flightLive = flightToken != null && AlarmDispatchGuard.activeFlight(flightGeneration, settings.generation,
                preferences.getLong(IN_FLIGHT_STARTED_ELAPSED, -1L), preferences.getLong(IN_FLIGHT_UNTIL_ELAPSED, -1L), elapsedNow) &&
                ProcessFlightExecution.registry.live(flightToken, preferences.getString(IN_FLIGHT_PROCESS, null), flightKey, flightTarget, elapsedNow)
            // A late synthetic boot broadcast must not erase a claim already made in this process.
            val resetClaims = !preserveDue && !(flightLive && flightEligible && flightGeneration == settings.generation)
            if (resetClaims && !clearClaimsLocked(preferences)) {
                return RegistrationResult(RegistrationCode.FAILED, "재부팅 이전의 실행 정보를 정리하지 못했습니다.")
            }
            val flightStatus = if (!resetClaims && flightToken != null && flightEligible && flightGeneration == settings.generation) {
                if (flightLive) RegistrationResult(RegistrationCode.IN_FLIGHT,
                    if (ProcessFlightExecution.registry.foregroundEntered(flightToken)) "자동주문 켜짐 · 전경 서비스에서 신청 작업 준비·대기 또는 처리 중입니다."
                    else "자동주문 켜짐 · 서비스 시작 확인 중입니다. 아직 준비·처리 진입은 확인되지 않았습니다.", alarmEpochMillis = flightTarget)
                else if (flightTarget > now.toEpochMilli()) RegistrationResult(RegistrationCode.BLOCKED,
                    "자동주문 켜짐 · 이전 준비 작업이 중단되었습니다. 앱 전면에서만 안전 조건을 확인해 재개할 수 있습니다.", alarmEpochMillis = flightTarget)
                else null
            } else null
            if (flightToken != null && (!flightEligible || flightGeneration != settings.generation) && !clearActiveFlightLocked(preferences)) {
                return RegistrationResult(RegistrationCode.FAILED, "지난 실행 정보를 정리하지 못했습니다. 결과를 확인하세요.")
            }
            val existingEpoch = preferences.getLong(ORDER_EPOCH, 0L)
            val existingToken = preferences.getString(ORDER_TOKEN, null)
            val existingGeneration = preferences.getLong(ORDER_GENERATION, -1L)
            val existingAccountGeneration = preferences.getLong(ORDER_ACCOUNT_GENERATION, -1L)
            val consumedTarget = preferences.getLong(CONSUMED_TARGET, 0L)
            val consumedGeneration = preferences.getLong(CONSUMED_GENERATION, -1L)
            val earlyWake = if (preserveDue) AlarmPlanSelector.existingEarlyWake(settings, plans, records, now,
                existingEpoch, existingGeneration, existingAccountGeneration, !existingToken.isNullOrEmpty(),
                preferences.getLong(WAKE_EPOCH, 0L), preferences.getLong(TARGET_WALL_EPOCH, existingEpoch), wallNow,
                consumedTarget, consumedGeneration) else null
            if (earlyWake != null) {
                val manager = context.getSystemService(AlarmManager::class.java)
                if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
                    return RegistrationResult(RegistrationCode.BLOCKED, "자동주문 켜짐 · 휴대폰의 정확 알람 설정이 필요합니다.")
                }
                // Keep the original registration; metadata does not prove OS delivery. Foreground recovery shares its token CAS.
                return RegistrationResult(RegistrationCode.REGISTERED,
                    "자동주문 켜짐 · 기존 사전 준비 등록 정보를 유지했습니다. 알람 전달과 서비스 실행 여부는 아직 확인되지 않았습니다.", alarmEpochMillis = existingEpoch)
            }
            val due = if (preserveDue && existingAccountGeneration == settings.accountGeneration &&
                !AlarmDispatchGuard.consumedTarget(existingEpoch, existingGeneration, consumedTarget, consumedGeneration))
                AlarmPlanSelector.existingDue(settings, plans, records, now, existingEpoch, existingGeneration, !existingToken.isNullOrEmpty()) else null
            if (due != null && existingToken != null) {
                val manager = context.getSystemService(AlarmManager::class.java)
                if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
                    return RegistrationResult(RegistrationCode.BLOCKED, "자동주문 켜짐 · 휴대폰의 정확 알람 설정이 필요합니다.")
                }
                if (!preferences.edit().putLong(ORDER_EPOCH, existingEpoch).putString(ORDER_TOKEN, existingToken)
                        .putLong(ORDER_GENERATION, existingGeneration).putLong(ORDER_ACCOUNT_GENERATION, settings.accountGeneration).commit()) {
                    OrderNotifier(context).blocked("예약 알람 정보를 저장하지 못해 알람 복구를 중단했습니다. 자동실행 ON 설정은 유지됩니다.")
                    return RegistrationResult(RegistrationCode.FAILED, "자동주문 켜짐 · 알람 정보를 저장하지 못해 등록에 실패했습니다.")
                }
                val wakeEpoch = preferences.getLong(WAKE_EPOCH, preferences.getLong(TARGET_WALL_EPOCH, existingEpoch))
                try {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeEpoch, pendingAlarm(context, false, existingToken))
                } catch (_: SecurityException) {
                    return RegistrationResult(RegistrationCode.FAILED, "자동주문 켜짐 · Android가 기존 알람 등록을 거부했습니다.")
                }
                return RegistrationResult(RegistrationCode.REGISTERED, "자동주문 켜짐 · 기존 신청 알람을 보존했습니다. 사전대기 실행 완료는 확인되지 않았습니다.", alarmEpochMillis = existingEpoch)
            }
            cancelScheduledLocked()
            if (!settings.masterEnabled) return RegistrationResult(RegistrationCode.STOPPED, "자동주문이 꺼져 있습니다.")
            settings.liveBlockedReason?.let { return RegistrationResult(RegistrationCode.BLOCKED, "자동주문 켜짐 · $it") }
            if (settings.liveScope == LiveScope.NONE) return RegistrationResult(RegistrationCode.STOPPED, "자동주문 설정은 켜져 있지만 실제구매 허용 범위가 지정되지 않았습니다.")
            if (!settings.displayPriceRiskAccepted) return RegistrationResult(RegistrationCode.BLOCKED, "자동주문 켜짐 · 실제구매 및 가격 위험 동의가 필요합니다.")
            val plan = AlarmPlanSelector.next(settings, plans, records, now, consumedTarget, consumedGeneration)
                ?: return flightStatus ?: RegistrationResult(RegistrationCode.NO_FUTURE_PLAN, "자동주문 켜짐 · 등록할 미래 신청 일정이 없습니다.")
            val epoch = plan.date.atTime(plan.time).atZone(ZONE).toInstant().toEpochMilli()
            val manager = context.getSystemService(AlarmManager::class.java)
            if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
                return RegistrationResult(RegistrationCode.BLOCKED, "자동주문 켜짐 · 휴대폰의 정확 알람 설정이 필요합니다.")
            }
            val wake = WarmupAlarmPlanner.plan(epoch, now.toEpochMilli(), wallNow, batteryExempt(context))
                ?: return RegistrationResult(RegistrationCode.FAILED, "사이트 시각과 기기 시각으로 실행 알람을 계산하지 못했습니다.")
            val token = UUID.randomUUID().toString()
            if (!preferences.edit().putLong(ORDER_EPOCH, epoch).putLong(TARGET_WALL_EPOCH, wake.targetWallEpochMillis)
                    .putLong(WAKE_EPOCH, wake.wakeEpochMillis).putBoolean(ORDER_WARMUP, wake.warmup)
                    .putString(ORDER_TOKEN, token).putLong(ORDER_GENERATION, settings.generation)
                    .putLong(ORDER_ACCOUNT_GENERATION, settings.accountGeneration).commit()) {
                OrderNotifier(context).blocked("예약 알람 정보를 저장하지 못해 알람을 등록하지 않았습니다. 자동실행 ON 설정은 유지됩니다.")
                return RegistrationResult(RegistrationCode.FAILED, "자동주문 켜짐 · 알람 정보를 저장하지 못해 등록에 실패했습니다.")
            }
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wake.wakeEpochMillis, pendingAlarm(context, false, token))
                val preparationEpoch = wake.targetWallEpochMillis - 60_000L
                if (settings.effectiveNotifications().preparation.enabled && preparationEpoch > wallNow) {
                    val preparationToken = UUID.randomUUID().toString()
                    if (!metadata(context).edit().putLong(PREP_EPOCH, preparationEpoch).putString(PREP_TOKEN, preparationToken).commit()) {
                        OrderNotifier(context).blocked("준비 알림 정보를 저장하지 못했습니다. 본 신청 알람은 유지됩니다.")
                        return RegistrationResult(RegistrationCode.REGISTERED, "자동주문 켜짐 · 신청 알람 등록됨 · 준비 알림 등록 실패 · ${wake.readiness}",
                            preparationWarning = "준비 알림 정보를 저장하지 못했습니다.", alarmEpochMillis = epoch)
                    }
                    manager.set(AlarmManager.RTC_WAKEUP, preparationEpoch, pendingAlarm(context, true, preparationToken))
                }
            } catch (_: SecurityException) {
                cancelScheduledLocked()
                return RegistrationResult(RegistrationCode.FAILED, "자동주문 켜짐 · Android가 알람 등록을 거부해 예약 알람을 취소했습니다.")
            }
            flightStatus?.copy(message = "${flightStatus.message} 다음 날짜 신청 알람은 별도로 등록했습니다.")
                ?: RegistrationResult(RegistrationCode.REGISTERED, "자동주문 켜짐 · 다음 신청 알람 등록됨 · ${wake.readiness}", alarmEpochMillis = epoch)
        }
    }

    /** Foreground-only claim or continuation of the original early wake, never a past-target catch-up. */
    suspend fun resumeInterruptedPreparation(activity: Activity): RegistrationResult? {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return null
        val hasForeground = withContext(Dispatchers.Main.immediate) { foregroundConfirmed(activity) }
        val settings = store.loadSettings()
        val now = clock.instant()
        val plans = ScheduleCalculator.upcomingPlans(settings, store.loadOverrides(), now.atZone(ZONE).toLocalDate(), 93)
        val records = store.loadRecords()
        var dispatch: RecoveryDispatch? = null
        val result = synchronized(METADATA_LOCK) {
            val preferences = metadata(context)
            val token = preferences.getString(IN_FLIGHT_TOKEN, null)
            if (token == null) {
                if (!hasForeground) return@synchronized null
                val original = preferences.getString(ORDER_TOKEN, null) ?: return@synchronized null
                val originalTarget = preferences.getLong(ORDER_EPOCH, 0L)
                val remaining = originalTarget - now.toEpochMilli()
                val targetWall = preferences.getLong(TARGET_WALL_EPOCH, originalTarget)
                val wallNow = System.currentTimeMillis()
                if (remaining !in 1..WarmupAlarmPlanner.PREPARATION_MAX_MILLIS ||
                    targetWall - wallNow !in 1..WarmupAlarmPlanner.PREPARATION_MAX_MILLIS ||
                    AlarmPlanSelector.existingEarlyWake(settings, plans, records, now, originalTarget,
                        preferences.getLong(ORDER_GENERATION, -1L), preferences.getLong(ORDER_ACCOUNT_GENERATION, -1L), true,
                        preferences.getLong(WAKE_EPOCH, 0L), targetWall, wallNow,
                        preferences.getLong(CONSUMED_TARGET, 0L), preferences.getLong(CONSUMED_GENERATION, -1L)) == null) {
                    return@synchronized null
                }
                // Receiver and foreground recovery share the same token CAS and persisted consumed claim.
                val claimed = OrderReceiver().consumeAlarm(context, Intent(ACTION_ORDER).putExtra(EXTRA_TOKEN, original), false)
                    ?: return@synchronized null
                val key = AlarmDispatchGuard.key(Instant.ofEpochMilli(claimed.orderEpoch).atZone(ZONE).toLocalDate(), claimed.generation)
                    ?: return@synchronized null
                dispatch = RecoveryDispatch(claimed.token, key, claimed.orderEpoch)
                return@synchronized RegistrationResult(RegistrationCode.BLOCKED,
                    "기존 사전 준비의 시작을 요청했습니다. 전경 서비스 진입은 아직 확인되지 않았습니다.", alarmEpochMillis = claimed.orderEpoch)
            }
            val target = preferences.getLong(IN_FLIGHT_TARGET, 0L)
            val generation = preferences.getLong(IN_FLIGHT_GENERATION, -1L)
            val key = AlarmDispatchGuard.key(Instant.ofEpochMilli(target).atZone(ZONE).toLocalDate(), generation)
            val registry = ProcessFlightExecution.registry
            val elapsed = SystemClock.elapsedRealtime()
            if (registry.live(token, preferences.getString(IN_FLIGHT_PROCESS, null), key, target, elapsed)) {
                return@synchronized null
            }
            if (InterruptedPreparationGuard.eligible(settings, plans, records, now, target, generation,
                preferences.getLong(IN_FLIGHT_ACCOUNT_GENERATION, -1L), preferences.getLong(CONSUMED_TARGET, 0L),
                preferences.getLong(CONSUMED_GENERATION, -1L), hasForeground) == null) {
                return@synchronized RegistrationResult(RegistrationCode.BLOCKED,
                    "준비 재개 조건을 만족하지 않습니다. 지난 시각·변경된 설정·보호된 주문 기록은 재개하지 않으니 주문내역을 확인하세요.", alarmEpochMillis = target)
            }
            val dispatchKey = key ?: return@synchronized RegistrationResult(RegistrationCode.BLOCKED, "준비 세대 정보를 확인할 수 없습니다.")
            if (!registry.reserve(token, dispatchKey, target, elapsed)) {
                return@synchronized RegistrationResult(RegistrationCode.BLOCKED, "기존 준비 작업이 이미 시작 중입니다. 중복 재개하지 않습니다.", alarmEpochMillis = target)
            }
            if (!preferences.edit().putString(IN_FLIGHT_PROCESS, registry.processNonce)
                    .putLong(IN_FLIGHT_STARTED_ELAPSED, elapsed).putLong(IN_FLIGHT_UNTIL_ELAPSED, elapsed + WarmupAlarmPlanner.SERVICE_MAX_MILLIS).commit()) {
                registry.release(token)
                return@synchronized RegistrationResult(RegistrationCode.FAILED, "중단된 준비의 재개 정보를 저장하지 못했습니다.", alarmEpochMillis = target)
            }
            dispatch = RecoveryDispatch(token, dispatchKey, target)
            RegistrationResult(RegistrationCode.BLOCKED, "준비 재개를 요청했습니다. 전경 서비스 진입은 아직 확인되지 않았습니다.", alarmEpochMillis = target)
        }
        val pending = dispatch ?: return result
        try {
            reschedule()
            return withContext(Dispatchers.Main.immediate) {
                val current = store.loadSettings()
                val currentNow = clock.instant()
                val currentPlans = ScheduleCalculator.upcomingPlans(current, store.loadOverrides(), currentNow.atZone(ZONE).toLocalDate(), 93)
                val stillEligible = synchronized(METADATA_LOCK) {
                    val preferences = metadata(context)
                    InterruptedPreparationGuard.eligible(current, currentPlans, store.loadRecords(), currentNow,
                        pending.target, pending.key.generation, preferences.getLong(IN_FLIGHT_ACCOUNT_GENERATION, -1L),
                        preferences.getLong(CONSUMED_TARGET, 0L), preferences.getLong(CONSUMED_GENERATION, -1L),
                        foregroundConfirmed(activity)) != null && serviceClaim(context, pending.token, pending.key)
                }
                if (!stillEligible) {
                    ProcessFlightExecution.registry.release(pending.token)
                    return@withContext RegistrationResult(RegistrationCode.BLOCKED,
                        "앱 전면 또는 준비 조건이 변경되어 재개하지 않았습니다. 기존 소비 기록은 유지합니다.", alarmEpochMillis = pending.target)
                }
                try {
                    dispatchService(context, Intent(context, OrderService::class.java).putExtra(OrderService.EXTRA_DATE, pending.key.date.toString())
                        .putExtra(OrderService.EXTRA_GENERATION, pending.key.generation).putExtra(OrderService.EXTRA_FLIGHT_TOKEN, pending.token))
                    result
                } catch (_: RuntimeException) {
                    ProcessFlightExecution.registry.release(pending.token)
                    RegistrationResult(RegistrationCode.BLOCKED, "Android가 중단된 준비의 재개를 막았습니다. 기존 소비 기록은 유지합니다.", alarmEpochMillis = pending.target)
                }
            }
        } catch (cancelled: CancellationException) {
            if (!ProcessFlightExecution.registry.foregroundEntered(pending.token)) ProcessFlightExecution.registry.release(pending.token)
            throw cancelled
        }
    }

    private fun foregroundConfirmed(activity: Activity): Boolean {
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        return activity.packageName == context.packageName && activity.applicationInfo.uid == context.applicationInfo.uid &&
            !activity.isFinishing && !activity.isDestroyed &&
            (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true &&
            process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private data class RecoveryDispatch(val token: String, val key: AlarmDispatchKey, val target: Long)

    @Synchronized fun restoreLocked() {
        restoreMetadata(context)
    }

    @Synchronized fun cancelAll() {
        synchronized(METADATA_LOCK) {
            cancelScheduledLocked()
            metadata(context).edit().clear().commit()
        }
    }

    private fun cancelScheduledLocked() {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(pendingAlarm(context, false, ""))
        manager.cancel(pendingAlarm(context, true, ""))
        metadata(context).edit().remove(ORDER_EPOCH).remove(TARGET_WALL_EPOCH).remove(WAKE_EPOCH).remove(ORDER_WARMUP)
            .remove(ORDER_TOKEN).remove(ORDER_GENERATION).remove(ORDER_ACCOUNT_GENERATION).remove(PREP_EPOCH).remove(PREP_TOKEN).commit()
    }

    companion object {
        internal val METADATA_LOCK = Any()
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        const val ACTION_ORDER = "com.fullmetalsonic.dosirak.ORDER_ALARM"
        const val ACTION_PREPARE = "com.fullmetalsonic.dosirak.PREPARE_ALARM"
        const val EXTRA_TOKEN = "alarm_token"
        const val ORDER_EPOCH = "order_epoch"
        const val ORDER_TOKEN = "order_token"
        const val ORDER_GENERATION = "order_generation"
        const val ORDER_ACCOUNT_GENERATION = "order_account_generation"
        const val TARGET_WALL_EPOCH = "target_wall_epoch"
        const val WAKE_EPOCH = "wake_epoch"
        const val ORDER_WARMUP = "order_warmup"
        const val IN_FLIGHT_TOKEN = "in_flight_token"
        const val IN_FLIGHT_TARGET = "in_flight_target"
        const val IN_FLIGHT_GENERATION = "in_flight_generation"
        const val IN_FLIGHT_ACCOUNT_GENERATION = "in_flight_account_generation"
        const val IN_FLIGHT_PROCESS = "in_flight_process"
        const val IN_FLIGHT_STARTED_ELAPSED = "in_flight_started_elapsed"
        const val IN_FLIGHT_UNTIL_ELAPSED = "in_flight_until_elapsed"
        const val CONSUMED_TARGET = "consumed_target"
        const val CONSUMED_GENERATION = "consumed_generation"
        const val PREP_EPOCH = "prepare_epoch"
        const val PREP_TOKEN = "prepare_token"

        fun metadata(context: Context) = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("alarm_metadata", Context.MODE_PRIVATE)

        private fun batteryExempt(context: Context) = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

        private fun clearActiveFlightLocked(preferences: android.content.SharedPreferences) = preferences.edit()
            .remove(IN_FLIGHT_TOKEN).remove(IN_FLIGHT_TARGET).remove(IN_FLIGHT_GENERATION)
            .remove(IN_FLIGHT_ACCOUNT_GENERATION).remove(IN_FLIGHT_PROCESS)
            .remove(IN_FLIGHT_STARTED_ELAPSED).remove(IN_FLIGHT_UNTIL_ELAPSED).commit()

        private fun clearClaimsLocked(preferences: android.content.SharedPreferences) = preferences.edit()
            .remove(IN_FLIGHT_TOKEN).remove(IN_FLIGHT_TARGET).remove(IN_FLIGHT_GENERATION)
            .remove(IN_FLIGHT_ACCOUNT_GENERATION).remove(IN_FLIGHT_PROCESS)
            .remove(IN_FLIGHT_STARTED_ELAPSED).remove(IN_FLIGHT_UNTIL_ELAPSED)
            .remove(CONSUMED_TARGET).remove(CONSUMED_GENERATION).commit()

        internal fun finishFlight(context: Context, token: String): Boolean = synchronized(METADATA_LOCK) {
            ProcessFlightExecution.registry.release(token)
            val preferences = metadata(context)
            if (preferences.getString(IN_FLIGHT_TOKEN, null) != token) true else clearActiveFlightLocked(preferences)
        }

        internal fun serviceClaim(context: Context, token: String, key: AlarmDispatchKey): Boolean = synchronized(METADATA_LOCK) {
            val preferences = metadata(context)
            preferences.getString(IN_FLIGHT_TOKEN, null) == token &&
                ProcessFlightExecution.registry.live(token, preferences.getString(IN_FLIGHT_PROCESS, null), key,
                    preferences.getLong(IN_FLIGHT_TARGET, 0L), SystemClock.elapsedRealtime())
        }

        internal fun foregroundFlight(context: Context, token: String, key: AlarmDispatchKey): Boolean = synchronized(METADATA_LOCK) {
            if (!serviceClaim(context, token, key)) return@synchronized false
            val registry = ProcessFlightExecution.registry
            if (registry.foregroundEntered(token)) return@synchronized true
            val elapsed = SystemClock.elapsedRealtime()
            if (!metadata(context).edit().putLong(IN_FLIGHT_STARTED_ELAPSED, elapsed)
                    .putLong(IN_FLIGHT_UNTIL_ELAPSED, elapsed + WarmupAlarmPlanner.SERVICE_MAX_MILLIS).commit()) return@synchronized false
            registry.foreground(token, key, elapsed)
        }

        fun restoreMetadata(context: Context) {
            synchronized(METADATA_LOCK) {
                val manager = context.getSystemService(AlarmManager::class.java)
                val preferences = metadata(context)
                val now = System.currentTimeMillis()
                if (!clearClaimsLocked(preferences)) {
                    OrderNotifier(context).blocked("재부팅 이전 실행 정보를 정리하지 못해 예약 복구를 중단했습니다.")
                    return
                }
                val editor = preferences.edit()
                var expired = false
                val target = preferences.getLong(ORDER_EPOCH, 0L)
                val targetWall = preferences.getLong(TARGET_WALL_EPOCH, target)
                if (targetWall <= now) {
                    editor.remove(ORDER_EPOCH).remove(TARGET_WALL_EPOCH).remove(WAKE_EPOCH).remove(ORDER_WARMUP).remove(ORDER_TOKEN).remove(ORDER_GENERATION).remove(ORDER_ACCOUNT_GENERATION)
                    expired = true
                }
                if (preferences.getLong(PREP_EPOCH, 0L) <= now) {
                    editor.remove(PREP_EPOCH).remove(PREP_TOKEN)
                    expired = true
                }
                if (expired && !editor.commit()) {
                    OrderNotifier(context).blocked("지난 알람 정보를 정리하지 못해 예약 복구를 중단했습니다.")
                    return
                }
                if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) return
                val orderToken = preferences.getString(ORDER_TOKEN, null)
                val restored = WarmupAlarmPlanner.restore(target, targetWall, preferences.getLong(WAKE_EPOCH, targetWall), now, batteryExempt(context))
                if (orderToken != null && restored != null) {
                    if (!preferences.edit().putLong(TARGET_WALL_EPOCH, restored.targetWallEpochMillis)
                            .putLong(WAKE_EPOCH, restored.wakeEpochMillis).putBoolean(ORDER_WARMUP, restored.warmup).commit()) return
                    try {
                        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, restored.wakeEpochMillis, alarmIntent(context, false, orderToken))
                    } catch (_: SecurityException) { }
                }
                val preparationEpoch = preferences.getLong(PREP_EPOCH, 0L)
                val preparationToken = preferences.getString(PREP_TOKEN, null)
                if (preparationToken != null && preparationEpoch > now) {
                    try {
                        manager.set(AlarmManager.RTC_WAKEUP, preparationEpoch, alarmIntent(context, true, preparationToken))
                    } catch (_: SecurityException) { }
                }
            }
        }

        private fun alarmIntent(context: Context, preparation: Boolean, token: String): PendingIntent =
            PendingIntent.getBroadcast(context, if (preparation) 2 else 1,
                Intent(context, OrderReceiver::class.java)
                    .setAction(if (preparation) ACTION_PREPARE else ACTION_ORDER)
                    .putExtra(EXTRA_TOKEN, token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
