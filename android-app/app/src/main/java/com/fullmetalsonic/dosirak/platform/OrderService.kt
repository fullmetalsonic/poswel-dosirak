package com.fullmetalsonic.dosirak.platform

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.UserManager
import com.fullmetalsonic.dosirak.runtime.RuntimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.ArrayDeque

class OrderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = ArrayDeque<Pair<LocalDate, Int>>()
    private var running: Job? = null
    private var activeDate: LocalDate? = null
    private var latestStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val date = runCatching { LocalDate.parse(intent?.getStringExtra(EXTRA_DATE)) }.getOrNull()
        if (date == null || !getSystemService(UserManager::class.java).isUserUnlocked) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val notification = OrderNotifier(this).foreground(date)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(OrderNotifier.FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(OrderNotifier.FOREGROUND_ID, notification)
        } catch (_: RuntimeException) {
            OrderNotifier(this).blocked("예약 처리 서비스를 시작하지 못했습니다. 주문내역을 확인하세요.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:order")
                .apply { setReferenceCounted(false); acquire(10 * 60_000L) }
        }
        if (date != activeDate && queue.none { it.first == date }) queue.addLast(date to startId)
        processNext()
        return START_NOT_STICKY
    }

    private fun processNext() {
        if (running?.isActive == true) return
        val task = queue.pollFirst()
        if (task == null) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(latestStartId)
            return
        }
        activeDate = task.first
        running = scope.launch {
            try {
                withContext(Dispatchers.IO) { RuntimeProvider.get(this@OrderService).engine.execute(task.first) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OrderNotifier(this@OrderService).blocked("예약 처리가 중단되었습니다. 결과를 확인하기 전 새로 신청하지 마세요.")
            } finally {
                if (isActiveService()) {
                    runCatching { withContext(Dispatchers.IO) { RuntimeProvider.get(this@OrderService).scheduler.reschedule() } }
                    activeDate = null
                    running = null
                    processNext()
                }
            }
        }
    }

    private fun isActiveService() = scope.coroutineContext[Job]?.isActive == true

    override fun onTimeout(startId: Int, fgsType: Int) {
        OrderNotifier(this).blocked("Android의 처리 시간 제한으로 중단되었습니다. 전송 여부를 주문내역에서 확인하세요.")
        queue.clear()
        scope.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object { const val EXTRA_DATE = "order_date" }
}
