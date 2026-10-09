package com.fullmetalsonic.dosirak.platform

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WarmupProbeTest {
    @Suppress("DEPRECATION")
    @Test fun boundedForegroundWakeLockStopsWithoutOrderRuntimeOrNetwork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("poswelWarmupProbe") == "true")
        assertTrue("Use the isolated runner so the production Application never starts", instrumentation is IsolatedWarmupProbeRunner)
        val context = instrumentation.targetContext
        assertEquals(android.app.Application::class.java, context.applicationContext.javaClass)
        val token = UUID.randomUUID().toString()
        val intent = Intent(context, WarmupProbeService::class.java).putExtra(WarmupProbeService.EXTRA_TOKEN, token)
        val manager = context.getSystemService(ActivityManager::class.java)
        fun foreground() = manager.getRunningServices(100).any { it.service.className == WarmupProbeService::class.java.name && it.foreground }
        try {
            context.startForegroundService(intent)
            await(5_000L) { WarmupProbeService.latest?.let { it.token == token && it.wakeLockAcquired && foreground() } == true }
            val running = requireNotNull(WarmupProbeService.latest)
            assertTrue(running.foregroundEntered)
            assertTrue(running.wakeLockAcquired)
            assertNull(running.error)
            await(25_000L) { WarmupProbeService.latest?.let { it.token == token && it.destroyed } == true && !foreground() }
            val finished = requireNotNull(WarmupProbeService.latest)
            assertNull(finished.error)
            assertTrue(finished.wakeLockReleased)
            assertTrue(finished.finishedElapsed - finished.startedElapsed >= WarmupProbeService.WAIT_MILLIS)
            assertTrue(finished.finishedElapsed - finished.startedElapsed < 25_000L)
            assertFalse(foreground())
        } finally {
            try {
                context.stopService(intent)
                await(5_000L) { !foreground() }
            } finally {
                val notifications = context.getSystemService(NotificationManager::class.java)
                notifications.deleteNotificationChannel("debug_warmup_probe")
                assertNull(notifications.getNotificationChannel("debug_warmup_probe"))
            }
        }
    }

    private fun await(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100L)
        assertTrue("Probe did not reach the required lifecycle state: ${WarmupProbeService.latest}", condition())
    }
}
