package com.fullmetalsonic.dosirak.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.fullmetalsonic.dosirak.runtime.RuntimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BackgroundCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BackgroundCheckScheduler.ACTION_CHECK ||
            !context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val token = intent.getStringExtra(BackgroundCheckScheduler.EXTRA_TOKEN) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return@launch
                val runtime = RuntimeProvider.get(context)
                runtime.backgroundCheck.dispatch(token) { runtime.scheduler.reschedule() }
            } catch (_: Exception) {
                // Local checks never retry a purchase or log account/order details.
            } finally {
                pending.finish()
            }
        }
    }
}
