package com.fullmetalsonic.dosirak.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.os.SystemClock
import com.fullmetalsonic.dosirak.runtime.RuntimeProvider
import com.fullmetalsonic.dosirak.domain.effectiveNotifications
import java.time.Instant

class OrderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        if (intent.action != ReservationScheduler.ACTION_ORDER && intent.action != ReservationScheduler.ACTION_PREPARE) {
            if (unlocked) {
                val afterBoot = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED || intent.action == Intent.ACTION_USER_UNLOCKED
                val runtime = RuntimeProvider.get(context)
                runtime.scheduler.reschedule(preserveDue = !afterBoot)
                runtime.backgroundCheck.reschedule(force = true)
            }
            else ReservationScheduler.restoreMetadata(context)
            return
        }
        val preparing = intent.action == ReservationScheduler.ACTION_PREPARE
        val captured = consumeAlarm(context, intent, preparing) ?: return
        if (!unlocked) {
            ProcessFlightExecution.registry.release(captured.token)
            OrderNotifier(context).blocked("재부팅 후 처음 잠금을 해제해야 예약을 실행할 수 있습니다. 지난 예약은 자동으로 신청하지 않습니다.")
            return
        }
        val runtime = RuntimeProvider.get(context)
        val settings = runtime.store.loadSettings()
        if (!settings.masterEnabled || !AlarmDispatchGuard.isCurrent(captured.generation, settings.generation) ||
            captured.accountGeneration != settings.accountGeneration) {
            if (!preparing) ReservationScheduler.finishFlight(context, captured.token)
            runtime.scheduler.reschedule()
            return
        }
        if (captured.orderEpoch <= 0L) return
        if (preparing && captured.targetWallEpoch <= System.currentTimeMillis()) return
        val date = Instant.ofEpochMilli(captured.orderEpoch).atZone(ReservationScheduler.ZONE).toLocalDate()
        if (preparing) {
            if (settings.effectiveNotifications().preparation.enabled) OrderNotifier(context).preparing(date)
            return
        }
        val generation = captured.generation ?: return
        // Persist the next date before service dispatch; a process death must not end recurring reservations.
        runtime.scheduler.reschedule()
        try {
            context.startForegroundService(Intent(context, OrderService::class.java)
                .putExtra(OrderService.EXTRA_DATE, date.toString()).putExtra(OrderService.EXTRA_GENERATION, generation)
                .putExtra(OrderService.EXTRA_FLIGHT_TOKEN, captured.token))
        } catch (_: RuntimeException) {
            ProcessFlightExecution.registry.release(captured.token)
            OrderNotifier(context).blocked("Android가 예약 작업의 시작을 막았습니다. 실행환경과 주문내역을 확인하세요.")
            runtime.scheduler.reschedule()
        }
    }

    internal fun consumeAlarm(context: Context, intent: Intent, preparing: Boolean): ConsumedAlarm? = synchronized(ReservationScheduler.METADATA_LOCK) {
        val preferences = ReservationScheduler.metadata(context)
        val tokenKey = if (preparing) ReservationScheduler.PREP_TOKEN else ReservationScheduler.ORDER_TOKEN
        val epochKey = if (preparing) ReservationScheduler.PREP_EPOCH else ReservationScheduler.ORDER_EPOCH
        val expected = preferences.getString(tokenKey, null) ?: return@synchronized null
        if (intent.getStringExtra(ReservationScheduler.EXTRA_TOKEN) != expected) return@synchronized null
        val epoch = if (preparing) preferences.getLong(epochKey, 0L)
            else preferences.getLong(ReservationScheduler.WAKE_EPOCH, preferences.getLong(epochKey, 0L))
        if (epoch <= 0L || epoch > System.currentTimeMillis()) return@synchronized null
        val orderEpoch = preferences.getLong(ReservationScheduler.ORDER_EPOCH, 0L)
        val targetWallEpoch = preferences.getLong(ReservationScheduler.TARGET_WALL_EPOCH, orderEpoch)
        val generation = preferences.getLong(ReservationScheduler.ORDER_GENERATION, -1L).takeIf { it >= 0L }
        val accountGeneration = preferences.getLong(ReservationScheduler.ORDER_ACCOUNT_GENERATION, -1L)
        val editor = preferences.edit().remove(tokenKey)
        if (preparing) editor.remove(epochKey)
        else {
            editor.remove(ReservationScheduler.WAKE_EPOCH)
            if (generation != null) {
                val started = SystemClock.elapsedRealtime()
                editor.putString(ReservationScheduler.IN_FLIGHT_TOKEN, expected)
                    .putString(ReservationScheduler.IN_FLIGHT_PROCESS, ProcessFlightExecution.registry.processNonce)
                    .putLong(ReservationScheduler.IN_FLIGHT_TARGET, orderEpoch)
                    .putLong(ReservationScheduler.IN_FLIGHT_GENERATION, generation)
                    .putLong(ReservationScheduler.IN_FLIGHT_ACCOUNT_GENERATION, accountGeneration)
                    .putLong(ReservationScheduler.IN_FLIGHT_STARTED_ELAPSED, started)
                    .putLong(ReservationScheduler.IN_FLIGHT_UNTIL_ELAPSED, started + WarmupAlarmPlanner.SERVICE_MAX_MILLIS)
                    .putLong(ReservationScheduler.CONSUMED_TARGET, orderEpoch)
                    .putLong(ReservationScheduler.CONSUMED_GENERATION, generation)
            }
        }
        if (!editor.commit()) {
            OrderNotifier(context).blocked("알람 처리 정보를 저장하지 못해 실행을 중단했습니다. 주문내역과 실행환경을 확인하세요.")
            return@synchronized null
        }
        if (!preparing && generation != null && orderEpoch > 0L) {
            val key = AlarmDispatchKey(Instant.ofEpochMilli(orderEpoch).atZone(ReservationScheduler.ZONE).toLocalDate(), generation)
            if (!ProcessFlightExecution.registry.reserve(expected, key, orderEpoch, SystemClock.elapsedRealtime())) {
                OrderNotifier(context).blocked("이미 시작된 준비 작업과 중복되어 실행을 중단했습니다. 주문내역을 확인하세요.")
                return@synchronized null
            }
        }
        ConsumedAlarm(orderEpoch, targetWallEpoch, generation, accountGeneration, expected)
    }

    internal data class ConsumedAlarm(val orderEpoch: Long, val targetWallEpoch: Long, val generation: Long?, val accountGeneration: Long, val token: String)
}
