package com.fullmetalsonic.dosirak.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.fullmetalsonic.dosirak.data.AppJson
import com.fullmetalsonic.dosirak.domain.*

/** A separate alarm identity: no reservation, account or order ledger access. */
class NotificationTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        runCatching {
            val event = NotificationEvent.valueOf(intent.getStringExtra("event") ?: "")
            val preferences = AppJson.gson.fromJson(intent.getStringExtra("preferences"), NotificationPreferences::class.java)
            val sound = intent.getStringExtra("sound")?.let { AppJson.gson.fromJson(it, FailureSoundRequest::class.java) }
            OrderNotifier(context).test(event, preferences, sound)
        }
    }

    companion object {
        private const val ACTION = "com.fullmetalsonic.dosirak.NOTIFICATION_TEST_ONLY"
        private fun pending(context: Context, intent: Intent = Intent(context, NotificationTestReceiver::class.java).setAction(ACTION)) =
            PendingIntent.getBroadcast(context, 207, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun schedule(context: Context, event: NotificationEvent, preferences: NotificationPreferences, sound: FailureSoundRequest?): Boolean = runCatching {
            val intent = Intent(context, NotificationTestReceiver::class.java).setAction(ACTION)
                .putExtra("event", event.name).putExtra("preferences", AppJson.gson.toJson(preferences.normalized()))
                .apply { if (sound != null) putExtra("sound", AppJson.gson.toJson(sound.normalized())) }
            context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 10_000, pending(context, intent))
            true
        }.getOrDefault(false)

        fun cancel(context: Context) { context.getSystemService(AlarmManager::class.java).cancel(pending(context)) }
    }
}
