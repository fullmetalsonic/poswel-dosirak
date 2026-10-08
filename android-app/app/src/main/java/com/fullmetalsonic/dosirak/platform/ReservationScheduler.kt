package com.fullmetalsonic.dosirak.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import com.fullmetalsonic.dosirak.data.AppStore
import com.fullmetalsonic.dosirak.domain.ScheduleCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class ReservationScheduler(context: Context, private val store: AppStore) {
    private val context = context.applicationContext

    @Synchronized fun reschedule(preserveDue: Boolean = true) {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
            restoreLocked()
            return
        }
        val settings = store.loadSettings()
        val now = Instant.now()
        val plans = ScheduleCalculator.upcomingPlans(settings, store.loadOverrides(), LocalDate.now(ZONE), 93)
        val records = store.loadRecords()
        val preferences = metadata(context)
        val existingEpoch = preferences.getLong(ORDER_EPOCH, 0L)
        val existingToken = preferences.getString(ORDER_TOKEN, null)
        val existingGeneration = preferences.getLong(ORDER_GENERATION, -1L)
        val due = if (preserveDue) AlarmPlanSelector.existingDue(settings, plans, records, now, existingEpoch, existingGeneration, !existingToken.isNullOrEmpty()) else null
        if (due != null && existingToken != null) {
            val manager = context.getSystemService(AlarmManager::class.java)
            if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) return
            if (!preferences.edit().putLong(ORDER_EPOCH, existingEpoch).putString(ORDER_TOKEN, existingToken)
                    .putLong(ORDER_GENERATION, existingGeneration).commit()) {
                OrderNotifier(context).blocked("예약 알람 정보를 저장하지 못해 알람 복구를 중단했습니다. 자동실행 ON 설정은 유지됩니다.")
                return
            }
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, existingEpoch, alarmIntent(context, false, existingToken))
            } catch (_: SecurityException) { }
            return
        }
        cancelAll()
        val plan = AlarmPlanSelector.next(settings, plans, records, now) ?: return
        val manager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) return
        val epoch = plan.date.atTime(plan.time).atZone(ZONE).toInstant().toEpochMilli()
        val token = UUID.randomUUID().toString()
        if (!metadata(context).edit().putLong(ORDER_EPOCH, epoch).putString(ORDER_TOKEN, token).putLong(ORDER_GENERATION, settings.generation).commit()) {
            OrderNotifier(context).blocked("예약 알람 정보를 저장하지 못해 알람을 등록하지 않았습니다. 자동실행 ON 설정은 유지됩니다.")
            return
        }
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, alarmIntent(context, false, token))
            val preparationEpoch = epoch - 60_000L
            if (settings.preparationAlert && preparationEpoch > now.toEpochMilli()) {
                val preparationToken = UUID.randomUUID().toString()
                if (!metadata(context).edit().putLong(PREP_EPOCH, preparationEpoch).putString(PREP_TOKEN, preparationToken).commit()) {
                    OrderNotifier(context).blocked("준비 알림 정보를 저장하지 못했습니다. 본 신청 알람은 유지됩니다.")
                    return
                }
                manager.set(AlarmManager.RTC_WAKEUP, preparationEpoch, alarmIntent(context, true, preparationToken))
            }
        } catch (_: SecurityException) {
            cancelAll()
        }
    }

    @Synchronized fun restoreLocked() {
        restoreMetadata(context)
    }

    @Synchronized fun cancelAll() {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(alarmIntent(context, false, ""))
        manager.cancel(alarmIntent(context, true, ""))
        metadata(context).edit().clear().commit()
    }

    companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        const val ACTION_ORDER = "com.fullmetalsonic.dosirak.ORDER_ALARM"
        const val ACTION_PREPARE = "com.fullmetalsonic.dosirak.PREPARE_ALARM"
        const val EXTRA_TOKEN = "alarm_token"
        const val ORDER_EPOCH = "order_epoch"
        const val ORDER_TOKEN = "order_token"
        const val ORDER_GENERATION = "order_generation"
        const val PREP_EPOCH = "prepare_epoch"
        const val PREP_TOKEN = "prepare_token"

        fun metadata(context: Context) = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("alarm_metadata", Context.MODE_PRIVATE)

        fun restoreMetadata(context: Context) {
            val manager = context.getSystemService(AlarmManager::class.java)
            val preferences = metadata(context)
            val now = System.currentTimeMillis()
            val editor = preferences.edit()
            var expired = false
            if (preferences.getLong(ORDER_EPOCH, 0L) <= now) {
                editor.remove(ORDER_EPOCH).remove(ORDER_TOKEN).remove(ORDER_GENERATION)
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
            listOf(false, true).forEach { preparation ->
                val epoch = preferences.getLong(if (preparation) PREP_EPOCH else ORDER_EPOCH, 0L)
                val token = preferences.getString(if (preparation) PREP_TOKEN else ORDER_TOKEN, null)
                if (token != null && epoch > System.currentTimeMillis()) {
                    try {
                        if (preparation) manager.set(AlarmManager.RTC_WAKEUP, epoch, alarmIntent(context, true, token))
                        else manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, alarmIntent(context, false, token))
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
