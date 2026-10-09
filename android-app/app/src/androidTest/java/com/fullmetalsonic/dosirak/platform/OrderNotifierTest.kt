package com.fullmetalsonic.dosirak.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class OrderNotifierTest {
    private class FakeDelivery : OrderNotificationDelivery {
        val channels = mutableMapOf<String, NotificationChannel>()
        val created = mutableListOf<NotificationChannel>()
        val notifications = mutableListOf<Pair<Int, Notification>>()
        var appEnabled = true
        var failDelivery = false
        override fun channel(id: String) = channels[id]
        override fun create(channel: NotificationChannel) { channels[channel.id] = channel; created.add(channel) }
        override fun enabled() = appEnabled
        override fun notify(id: Int, notification: Notification) {
            if (failDelivery) throw IllegalStateException("test delivery failure")
            notifications.add(id to notification)
        }
        override fun cancel(id: Int) = Unit
    }

    private class TestContext(base: Context, private val permissionGranted: Boolean = true) : ContextWrapper(base) {
        private val prefix = "notification-test-${UUID.randomUUID()}-"
        override fun getApplicationContext(): Context = this
        override fun checkSelfPermission(permission: String): Int = if (permissionGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
    }

    private val context: Context get() = TestContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private val date = LocalDate.of(2026, 10, 9)

    @Test fun allTwentyFourProfilesHaveExpectedEffectsAndNeverMultiplyOnRepeatedUse() {
        val delivery = FakeDelivery()
        val notifier = OrderNotifier(context, { AppSettings() }, delivery)
        NotificationEvent.values().forEach { event ->
            for (bits in 0..7) {
                val options = AlertOptions(popup = bits and 4 != 0, sound = bits and 2 != 0, vibration = bits and 1 != 0)
                val preferences = NotificationPreferences(options, options, options)
                notifier.test(event, preferences)
                val notification = delivery.notifications.last().second
                val channel = delivery.channels.getValue(notification.channelId)
                assertEquals(if (options.popup) NotificationManager.IMPORTANCE_HIGH else if (options.sound || options.vibration)
                    NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW, channel.importance)
                assertEquals(options.sound, channel.sound != null); assertEquals(options.vibration, channel.shouldVibrate())
                assertTrue(notification.extras.getString(Notification.EXTRA_TITLE)!!.contains("시험"))
                notifier.test(event, preferences)
            }
        }
        assertEquals(25, delivery.channels.size); assertEquals(25, delivery.created.size)
        assertEquals(48, delivery.notifications.size)
    }

    @Test fun disabledEventsAndAllMethodsOffCreateNoEventChannelOrNotification() {
        val delivery = FakeDelivery()
        val notifier = OrderNotifier(context, { AppSettings() }, delivery)
        listOf(AlertOptions(enabled = false, popup = true, sound = true, vibration = true), AlertOptions(statusBar = false))
            .forEach { off -> NotificationEvent.values().forEach { notifier.test(it, NotificationPreferences(off, off, off)) } }
        assertEquals(1, delivery.created.size); assertTrue(delivery.notifications.isEmpty())
        delivery.appEnabled = false
        notifier.test(NotificationEvent.FAILURE, NotificationPreferences())
        assertEquals(1, delivery.created.size); assertTrue(delivery.notifications.isEmpty())
    }

    @Test fun skippedAndPlannedAreSilentAndCompletedIsTheOnlySuccessEvent() {
        val delivery = FakeDelivery()
        val notifier = OrderNotifier(context, { AppSettings(notifications = NotificationPreferences()) }, delivery)
        ExecutionStatus.values().forEach { status ->
            val before = delivery.notifications.size
            notifier.result(ExecutionRecord(date, 2, status, "test", "message"))
            val event = when (status) {
                ExecutionStatus.COMPLETED -> NotificationEvent.SUCCESS
                ExecutionStatus.FAILED, ExecutionStatus.NEEDS_CHECK -> NotificationEvent.FAILURE
                else -> null
            }
            assertEquals(before + if (event == null) 0 else 1, delivery.notifications.size)
            if (event != null) assertTrue(delivery.notifications.last().second.channelId.contains(event.name.lowercase()))
        }
    }

    @Test fun settingsLoaderControlsPreparationResultsAndBlockedButNotMandatoryProgress() {
        val off = AlertOptions(enabled = false)
        var settings = AppSettings(notifications = NotificationPreferences(off, off, off))
        val delivery = FakeDelivery(); val notifier = OrderNotifier(context, { settings }, delivery)
        notifier.preparing(date); notifier.blocked("blocked")
        notifier.result(ExecutionRecord(date, 1, ExecutionStatus.COMPLETED, "VERIFIED", "done"))
        notifier.result(ExecutionRecord(date, 1, ExecutionStatus.FAILED, "HISTORY", "failed"))
        assertTrue(delivery.notifications.isEmpty())
        notifier.progress(date, "progress")
        assertEquals(OrderNotifier.PROGRESS_CHANNEL, delivery.notifications.single().second.channelId)
        assertEquals(OrderNotifier.PROGRESS_CHANNEL, notifier.foreground(date).channelId)
        settings = settings.copy(notifications = NotificationPreferences())
        notifier.preparing(date); notifier.blocked("blocked")
        assertEquals(3, delivery.notifications.size)
    }

    @Test fun legacySystemChannelBlocksCannotBeBypassedByAnyProfileOrTest() {
        val delivery = FakeDelivery()
        listOf(OrderNotifier.PROGRESS_CHANNEL, OrderNotifier.RESULT_CHANNEL, OrderNotifier.FAILURE_CHANNEL).forEach {
            delivery.channels[it] = NotificationChannel(it, "blocked", NotificationManager.IMPORTANCE_NONE)
        }
        val notifier = OrderNotifier(context, { AppSettings(notifications = NotificationPreferences()) }, delivery)
        NotificationEvent.values().forEach { event ->
            notifier.test(event, NotificationPreferences())
            assertEquals(when (event) {
                NotificationEvent.PREPARATION -> OrderNotifier.PROGRESS_CHANNEL
                NotificationEvent.SUCCESS -> OrderNotifier.RESULT_CHANNEL
                NotificationEvent.FAILURE -> OrderNotifier.FAILURE_CHANNEL
            }, notifier.notificationSettingsIntent(event).getStringExtra(Settings.EXTRA_CHANNEL_ID))
        }
        assertTrue(delivery.created.isEmpty()); assertTrue(delivery.notifications.isEmpty())
    }

    @Test fun blockedPreviousProfilePreventsCreatingNewProfileAndSettingsLinksToBlockedChannel() {
        val delivery = FakeDelivery()
        val testContext = context
        val notifier = OrderNotifier(testContext, { AppSettings(notifications = NotificationPreferences()) }, delivery)
        notifier.test(NotificationEvent.SUCCESS, NotificationPreferences())
        val previousId = delivery.notifications.single().second.channelId
        delivery.channels[previousId] = NotificationChannel(previousId, "blocked", NotificationManager.IMPORTANCE_NONE)
        val reopened = OrderNotifier(testContext, { AppSettings(notifications = NotificationPreferences(success = AlertOptions(popup = true))) }, delivery)
        reopened.test(NotificationEvent.SUCCESS, NotificationPreferences(success = AlertOptions(popup = true, sound = true, vibration = true)))
        reopened.result(ExecutionRecord(date, 1, ExecutionStatus.COMPLETED, "VERIFIED", "done"))
        assertEquals(2, delivery.created.size); assertEquals(1, delivery.notifications.size)
        assertEquals(previousId, reopened.notificationSettingsIntent(NotificationEvent.SUCCESS).getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test fun testsReturnDistinctSentDisabledAndAppBlockedResultsWithoutPostingWhenBlocked() {
        val delivery = FakeDelivery()
        val notifier = OrderNotifier(context, { AppSettings() }, delivery)
        assertEquals(NotificationTestCode.EVENT_DISABLED, notifier.test(NotificationEvent.SUCCESS,
            NotificationPreferences(success = AlertOptions(enabled = false))))
        assertTrue(delivery.notifications.isEmpty()); assertEquals(1, delivery.created.size)
        delivery.appEnabled = false
        assertEquals(NotificationTestCode.APP_DISABLED, notifier.test(NotificationEvent.FAILURE, NotificationPreferences()))
        assertFalse(notifier.notificationsAllowed()); assertTrue(delivery.notifications.isEmpty()); assertEquals(1, delivery.created.size)
        delivery.appEnabled = true
        assertEquals(NotificationTestCode.SENT, notifier.test(NotificationEvent.SUCCESS, NotificationPreferences()))
        assertEquals(1, delivery.notifications.size)
    }

    @Test fun deniedRuntimePermissionReportsAppBlockedWithoutPermissionChangesOrNotification() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val delivery = FakeDelivery()
        val denied = TestContext(InstrumentationRegistry.getInstrumentation().targetContext, false)
        val notifier = OrderNotifier(denied, { AppSettings() }, delivery)
        assertEquals(NotificationTestCode.APP_DISABLED, notifier.test(NotificationEvent.FAILURE, NotificationPreferences()))
        assertFalse(notifier.notificationsAllowed()); assertTrue(delivery.notifications.isEmpty()); assertEquals(1, delivery.created.size)
    }

    @Test fun blockedCurrentProfileReportsChannelAndCannotBeBypassedByChangingDraft() {
        val delivery = FakeDelivery()
        val blocked = "reservation_success_p0"
        delivery.channels[blocked] = NotificationChannel(blocked, "blocked", NotificationManager.IMPORTANCE_NONE)
        val notifier = OrderNotifier(context, { AppSettings() }, delivery)
        assertEquals(NotificationTestCode.CHANNEL_BLOCKED, notifier.test(NotificationEvent.SUCCESS, NotificationPreferences()))
        assertEquals(blocked, notifier.notificationSettingsIntent(NotificationEvent.SUCCESS).getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(NotificationTestCode.CHANNEL_BLOCKED, notifier.test(NotificationEvent.SUCCESS,
            NotificationPreferences(success = AlertOptions(popup = true, sound = true))))
        assertTrue(delivery.notifications.isEmpty()); assertEquals(1, delivery.created.size)
    }

    @Test fun failedDeliveryIsNotReportedAsSent() {
        val delivery = FakeDelivery().apply { failDelivery = true }
        val notifier = OrderNotifier(context, { AppSettings() }, delivery)
        assertEquals(NotificationTestCode.FAILED, notifier.test(NotificationEvent.SUCCESS, NotificationPreferences()))
        assertTrue(delivery.notifications.isEmpty())
    }
}
