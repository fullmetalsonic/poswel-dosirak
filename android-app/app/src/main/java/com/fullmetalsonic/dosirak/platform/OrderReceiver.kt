package com.fullmetalsonic.dosirak.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.fullmetalsonic.dosirak.runtime.RuntimeProvider
import java.time.Instant

class OrderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        if (intent.action != ReservationScheduler.ACTION_ORDER && intent.action != ReservationScheduler.ACTION_PREPARE) {
            if (unlocked) {
                val afterBoot = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED || intent.action == Intent.ACTION_USER_UNLOCKED
                RuntimeProvider.get(context).scheduler.reschedule(preserveDue = !afterBoot)
            }
            else ReservationScheduler.restoreMetadata(context)
            return
        }
        val preparing = intent.action == ReservationScheduler.ACTION_PREPARE
        val preferences = ReservationScheduler.metadata(context)
        val tokenKey = if (preparing) ReservationScheduler.PREP_TOKEN else ReservationScheduler.ORDER_TOKEN
        val epochKey = if (preparing) ReservationScheduler.PREP_EPOCH else ReservationScheduler.ORDER_EPOCH
        val expected = preferences.getString(tokenKey, null) ?: return
        if (intent.getStringExtra(ReservationScheduler.EXTRA_TOKEN) != expected) return
        val epoch = preferences.getLong(epochKey, 0L)
        if (epoch <= 0L || epoch > System.currentTimeMillis()) return
        val editor = preferences.edit().remove(tokenKey).remove(epochKey)
        if (!preparing) editor.remove(ReservationScheduler.ORDER_GENERATION)
        if (!editor.commit()) {
            OrderNotifier(context).blocked("알람 처리 정보를 저장하지 못해 실행을 중단했습니다. 주문내역과 실행환경을 확인하세요.")
            return
        }
        if (!unlocked) {
            OrderNotifier(context).blocked("재부팅 후 처음 잠금을 해제해야 예약을 실행할 수 있습니다. 지난 예약은 자동으로 신청하지 않습니다.")
            return
        }
        val runtime = RuntimeProvider.get(context)
        if (!runtime.store.loadSettings().masterEnabled) {
            runtime.scheduler.reschedule()
            return
        }
        val orderEpoch = if (preparing) preferences.getLong(ReservationScheduler.ORDER_EPOCH, 0L) else epoch
        if (orderEpoch <= 0L) return
        if (preparing && orderEpoch <= System.currentTimeMillis()) return
        val date = Instant.ofEpochMilli(orderEpoch).atZone(ReservationScheduler.ZONE).toLocalDate()
        if (preparing) {
            OrderNotifier(context).preparing(date)
            return
        }
        runtime.scheduler.reschedule()
        try {
            context.startForegroundService(Intent(context, OrderService::class.java).putExtra(OrderService.EXTRA_DATE, date.toString()))
        } catch (_: RuntimeException) {
            OrderNotifier(context).blocked("Android가 예약 작업의 시작을 막았습니다. 실행환경과 주문내역을 확인하세요.")
            runtime.scheduler.reschedule()
        }
    }
}
