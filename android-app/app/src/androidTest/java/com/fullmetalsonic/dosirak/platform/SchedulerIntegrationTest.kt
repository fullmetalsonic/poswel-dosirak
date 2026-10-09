package com.fullmetalsonic.dosirak.platform

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.data.AppStore
import com.fullmetalsonic.dosirak.domain.AppSettings
import com.fullmetalsonic.dosirak.domain.DateOverride
import com.fullmetalsonic.dosirak.domain.DatePolicy
import com.fullmetalsonic.dosirak.domain.ExecutionRecord
import com.fullmetalsonic.dosirak.domain.ExecutionStatus
import com.fullmetalsonic.dosirak.domain.LiveScope
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real AlarmManager, isolated storage, undeliverable test components. No service, engine or gateway. */
@RunWith(AndroidJUnit4::class)
class SchedulerIntegrationTest {
    private lateinit var context: Context
    private lateinit var base: Context
    private lateinit var store: AppStore
    private lateinit var scheduler: ReservationScheduler
    private lateinit var prefs: SharedPreferences
    private lateinit var databaseName: String
    private lateinit var metadataName: String
    private lateinit var action: String
    private var requestCode = 0
    private val alarmFactories = mutableListOf<Pair<Boolean, String>>()
    private val flightTokens = mutableSetOf<String>()
    private val dispatches = mutableListOf<Intent>()
    private var beforeAlarmRegistration: (() -> Unit)? = null
    private val day = LocalDate.of(2030, 1, 7)
    private val target = day.atTime(6, 0).atZone(ReservationScheduler.ZONE).toInstant()
    private val logicalClock = MutableClock(target.minusSeconds(300))
    private val enabled = AppSettings(masterEnabled = true, liveScope = LiveScope.RECURRING,
        displayPriceRiskAccepted = true, generation = 81, accountGeneration = 9,
        backgroundCheckEnabled = false, preparationAlert = false)

    @Before fun prepare() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("poswelSchedulerSandbox") == "true") {
            "Explicit isolated scheduler sandbox argument is required"
        }
        check(instrumentation is IsolatedWarmupProbeRunner) { "Production Application must never start" }
        base = instrumentation.targetContext
        check(base.applicationContext.javaClass == Application::class.java)
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && Build.MODEL.contains("sdk", ignoreCase = true)) {
            "Scheduler integration is permitted only on the isolated SDK emulator"
        }
        val manager = base.getSystemService(AlarmManager::class.java)
        check(Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) { "Sandbox exact alarm permission required" }
        val id = UUID.randomUUID().toString()
        databaseName = "scheduler-test-$id.db"
        metadataName = "scheduler-test-$id"
        action = "${instrumentation.context.packageName}.SCHEDULER_TEST.$id"
        requestCode = 10_000 + (id.hashCode().and(0x3fffffff))
        context = SandboxContext(base, metadataName)
        prefs = ReservationScheduler.metadata(context)
        store = AppStore(context, databaseName)
        scheduler = ReservationScheduler(context, store, logicalClock, pendingAlarm = { _, preparation, token ->
            alarmFactories += preparation to token
            if (!preparation && token.isNotEmpty()) beforeAlarmRegistration?.invoke()
            // This explicit component does not exist in the test APK, so even an unexpected delivery cannot run code.
            val intent = Intent().setComponent(ComponentName(instrumentation.context.packageName,
                "${instrumentation.context.packageName}.SchedulerNoopReceiver"))
                .setAction("$action.${if (preparation) "prepare" else "order"}")
                .putExtra(ReservationScheduler.EXTRA_TOKEN, token)
            PendingIntent.getBroadcast(base, requestCode + if (preparation) 1 else 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }, dispatchService = { _, intent -> dispatches += Intent(intent) })
        store.saveSettings(enabled)
        (0L..2L).forEach { store.saveOverride(DateOverride(day.plusDays(it), DatePolicy.MANUAL, 1, LocalTime.of(6, 0))) }
    }

    @After fun clean() {
        try {
            if (::scheduler.isInitialized) scheduler.cancelAll()
        } finally {
            flightTokens.forEach { ProcessFlightExecution.registry.release(it) }
            if (::store.isInitialized) store.close()
            if (::databaseName.isInitialized) base.deleteDatabase(databaseName)
            if (::metadataName.isInitialized) base.createDeviceProtectedStorageContext().deleteSharedPreferences(metadataName)
        }
    }

    @Test fun applicationRefreshBeforeReceiverRetainsOriginalEarlyTokenWithoutRearming() {
        val token = registerDeliveredEarlyWake()
        val metadata = prefs.all.toMap()
        val factories = alarmFactories.size
        assertEquals(RegistrationCode.REGISTERED, scheduler.reschedule().code)
        assertEquals(metadata, prefs.all)
        assertEquals("No cancel or re-arm of the already delivered idle alarm", factories, alarmFactories.size)
        assertNotNull(consume(token))
        assertNull(consume(token))
        assertEquals(RegistrationCode.IN_FLIGHT, scheduler.reschedule().code)
        assertNextDayAlarm(day.plusDays(1))
        assertTrue(ReservationScheduler.serviceClaim(context, token, AlarmDispatchKey(day, enabled.generation)))
    }

    @Test fun receiverBeforeApplicationRefreshKeepsOneFlightAndIndependentNextDayAlarm() {
        val token = registerDeliveredEarlyWake()
        assertNotNull(consume(token))
        assertEquals(RegistrationCode.IN_FLIGHT, scheduler.reschedule().code)
        assertNextDayAlarm(day.plusDays(1))
        val nextToken = prefs.getString(ReservationScheduler.ORDER_TOKEN, null)
        assertNotEquals(token, nextToken)
        assertNull(consume(token))
        assertEquals(token, prefs.getString(ReservationScheduler.IN_FLIGHT_TOKEN, null))
        assertTrue(ReservationScheduler.serviceClaim(context, token, AlarmDispatchKey(day, enabled.generation)))
    }

    @Test fun networkPreparationFailureWithCheckOffSchedulesNextDayAndPreservesConsumedAndProtectedLedger() {
        val token = registerDeliveredEarlyWake()
        assertNotNull(consume(token))
        scheduler.reschedule() // Same ordering as Receiver, before dispatch and any engine failure.
        assertNextDayAlarm(day.plusDays(1))
        val failed = ExecutionRecord(day, 1, ExecutionStatus.FAILED, "NETWORK", "fixture preparation failure")
        val protected = ExecutionRecord(day.plusDays(1), 1, ExecutionStatus.NEEDS_CHECK, "SUBMIT", "unknown",
            submissionPossible = true, accountGeneration = enabled.accountGeneration, generation = enabled.generation)
        store.record(failed)
        store.record(protected)
        assertTrue(ReservationScheduler.finishFlight(context, token))
        assertEquals(RegistrationCode.REGISTERED, scheduler.reschedule().code)
        assertNextDayAlarm(day.plusDays(2))
        assertEquals(target.toEpochMilli(), prefs.getLong(ReservationScheduler.CONSUMED_TARGET, 0L))
        assertEquals(enabled.generation, prefs.getLong(ReservationScheduler.CONSUMED_GENERATION, -1L))
        assertEquals(setOf(failed, protected), store.loadRecords().toSet())
        assertEquals(enabled, store.loadSettings())
        assertNull(prefs.getString(ReservationScheduler.IN_FLIGHT_TOKEN, null))
    }

    @Test fun processDeathOrRejectedDispatchWithoutFinallyRetainsRecoverableClaimAndNextDayAlarm() {
        val token = registerDeliveredEarlyWake()
        assertNotNull(consume(token))
        scheduler.reschedule()
        assertNextDayAlarm(day.plusDays(1))
        ProcessFlightExecution.registry.release(token) // Equivalent to dead process / rejected FGS dispatch, without cleanup.
        assertEquals(RegistrationCode.BLOCKED, scheduler.reschedule().code)
        assertEquals(token, prefs.getString(ReservationScheduler.IN_FLIGHT_TOKEN, null))
        assertEquals(target.toEpochMilli(), prefs.getLong(ReservationScheduler.CONSUMED_TARGET, 0L))
        assertNextDayAlarm(day.plusDays(1))
        assertNull(consume(token))
    }

    @Test fun singleDateDoesNotRegisterAnotherDateWhileCurrentClaimRemains() {
        store.saveSettings(enabled.copy(liveScope = LiveScope.SINGLE_DATE, liveTestDate = day))
        val token = registerDeliveredEarlyWake()
        assertNotNull(consume(token))
        assertEquals(RegistrationCode.IN_FLIGHT, scheduler.reschedule().code)
        assertNull(prefs.getString(ReservationScheduler.ORDER_TOKEN, null))
        assertEquals(token, prefs.getString(ReservationScheduler.IN_FLIGHT_TOKEN, null))
        assertEquals("No pending ORDER alarm for another date", 0, pendingOrderAlarmCount())
    }

    @Test fun earlyWakeIsNotPreservedForFutureWakeChangedGenerationAccountOffOrProtectedRecord() {
        val configurations = listOf(enabled, enabled.copy(generation = enabled.generation + 1),
            enabled.copy(accountGeneration = enabled.accountGeneration + 1), enabled.copy(masterEnabled = false))
        configurations.forEachIndexed { index, changed ->
            scheduler.cancelAll()
            store.saveSettings(enabled)
            val token = registerDeliveredEarlyWake()
            if (index == 0) check(prefs.edit().putLong(ReservationScheduler.WAKE_EPOCH, System.currentTimeMillis() + 30_000L).commit())
            store.saveSettings(changed)
            scheduler.reschedule()
            assertNotEquals(token, prefs.getString(ReservationScheduler.ORDER_TOKEN, null))
            if (!changed.masterEnabled) assertNull(prefs.getString(ReservationScheduler.ORDER_TOKEN, null))
        }
        scheduler.cancelAll()
        store.saveSettings(enabled)
        val token = registerDeliveredEarlyWake()
        val protected = ExecutionRecord(day, 1, ExecutionStatus.NEEDS_CHECK, "SUBMIT", "unknown", submissionPossible = true)
        store.record(protected)
        scheduler.reschedule()
        assertNotEquals(token, prefs.getString(ReservationScheduler.ORDER_TOKEN, null))
        assertNextDayAlarm(day.plusDays(1))
        assertEquals(listOf(protected), store.loadRecords())
    }

    @Test fun consumedMetadataCannotPreserveEarlyWakeAndMasterOffCancelsFutureAlarm() {
        val token = registerDeliveredEarlyWake()
        check(prefs.edit().putLong(ReservationScheduler.CONSUMED_TARGET, target.toEpochMilli())
            .putLong(ReservationScheduler.CONSUMED_GENERATION, enabled.generation).commit())
        scheduler.reschedule()
        assertNotEquals(token, prefs.getString(ReservationScheduler.ORDER_TOKEN, null))
        assertNextDayAlarm(day.plusDays(1))
        store.saveSettings(enabled.copy(masterEnabled = false))
        assertEquals(RegistrationCode.STOPPED, scheduler.reschedule().code)
        assertNull(prefs.getString(ReservationScheduler.ORDER_TOKEN, null))
        assertEquals("Master OFF cancels the pending ORDER alarm", 0, pendingOrderAlarmCount())
    }

    @Test fun bootAfterWarmupUsesOnlyFutureColdTargetAndNeverCatchesUpPastTarget() {
        val token = registerDeliveredEarlyWake()
        assertEquals(RegistrationCode.REGISTERED, scheduler.reschedule(preserveDue = false).code)
        assertNotEquals(token, prefs.getString(ReservationScheduler.ORDER_TOKEN, null))
        assertFalse(prefs.getBoolean(ReservationScheduler.ORDER_WARMUP, true))
        assertEquals(prefs.getLong(ReservationScheduler.TARGET_WALL_EPOCH, 0L), prefs.getLong(ReservationScheduler.WAKE_EPOCH, -1L))
        assertTrue(prefs.getLong(ReservationScheduler.WAKE_EPOCH, 0L) > System.currentTimeMillis())
        logicalClock.now = target.plusSeconds(1)
        scheduler.reschedule(preserveDue = false)
        assertNextDayAlarm(day.plusDays(1))
    }

    @Test fun foregroundClaimsOriginalWakeWhenOnlyMetadataAndPendingIntentRemain() = withForeground { activity, _ ->
        val token = registerDeliveredEarlyWake()
        cancelOsOrderAlarmOnly(token)
        assertEquals("Only metadata and PendingIntent remain after OS alarm cancellation", 0, pendingOrderAlarmCount())
        assertEquals(token, prefs.getString(ReservationScheduler.ORDER_TOKEN, null))
        runBlocking { scheduler.resumeInterruptedPreparation(activity) }
        assertEquals(1, dispatches.size)
        assertEquals(token, dispatches.single().getStringExtra(OrderService.EXTRA_FLIGHT_TOKEN))
        flightTokens += token
        assertNull(consume(token))
        assertNextDayAlarm(day.plusDays(1))
        runBlocking { assertNull(scheduler.resumeInterruptedPreparation(activity)) }
        assertEquals(1, dispatches.size)
    }

    @Test fun receiverFirstMakesForegroundRecoveryAOneClaimNoOp() = withForeground { activity, _ ->
        val token = registerDeliveredEarlyWake()
        assertNotNull(consume(token))
        scheduler.reschedule()
        runBlocking { assertNull(scheduler.resumeInterruptedPreparation(activity)) }
        assertTrue(dispatches.isEmpty())
        assertNull(consume(token))
        assertNextDayAlarm(day.plusDays(1))
    }

    @Test fun foregroundAndReceiverRaceHaveOneAtomicClaimWinner() = withForeground { activity, _ ->
        val token = registerDeliveredEarlyWake()
        val start = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val receiver = executor.submit<OrderReceiver.ConsumedAlarm?> {
                check(start.await(5, TimeUnit.SECONDS))
                OrderReceiver().consumeAlarm(context, Intent(ReservationScheduler.ACTION_ORDER)
                    .putExtra(ReservationScheduler.EXTRA_TOKEN, token), false)
            }
            runBlocking {
                val foreground = async(Dispatchers.Default) {
                    check(start.await(5, TimeUnit.SECONDS))
                    scheduler.resumeInterruptedPreparation(activity)
                }
                start.countDown()
                foreground.await()
            }
            val receiverWon = receiver.get(5, TimeUnit.SECONDS) != null
            flightTokens += token
            assertEquals(1, (if (receiverWon) 1 else 0) + dispatches.size)
            assertEquals(token, prefs.getString(ReservationScheduler.IN_FLIGHT_TOKEN, null))
            assertNull(consume(token))
            scheduler.reschedule() // Receiver winner would persist the next date before service dispatch.
            assertNextDayAlarm(day.plusDays(1))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun lateBootBroadcastKeepsLiveForegroundClaimAndNextDayAlarm() = withForeground { activity, _ ->
        val token = registerDeliveredEarlyWake()
        runBlocking { scheduler.resumeInterruptedPreparation(activity) }
        flightTokens += token
        assertEquals(1, dispatches.size)
        assertEquals(RegistrationCode.IN_FLIGHT, scheduler.reschedule(preserveDue = false).code)
        assertTrue(ReservationScheduler.serviceClaim(context, token, AlarmDispatchKey(day, enabled.generation)))
        assertEquals(token, prefs.getString(ReservationScheduler.IN_FLIGHT_TOKEN, null))
        assertEquals(target.toEpochMilli(), prefs.getLong(ReservationScheduler.CONSUMED_TARGET, 0L))
        assertNextDayAlarm(day.plusDays(1))
        runBlocking { assertNull(scheduler.resumeInterruptedPreparation(activity)) }
        assertEquals(1, dispatches.size)
    }

    @Test fun foregroundRecoveryRejectsChangedSettingsLedgerPastTargetAndTooEarlyWindow() = withForeground { activity, _ ->
        val configurations = listOf(enabled.copy(masterEnabled = false), enabled.copy(generation = enabled.generation + 1),
            enabled.copy(accountGeneration = enabled.accountGeneration + 1), enabled.copy(displayPriceRiskAccepted = false),
            enabled.copy(liveScope = LiveScope.NONE), enabled.copy(liveBlockedReason = "blocked"))
        configurations.forEach { changed ->
            scheduler.cancelAll()
            store.saveSettings(enabled)
            val token = registerDeliveredEarlyWake()
            store.saveSettings(changed)
            runBlocking { assertNull(scheduler.resumeInterruptedPreparation(activity)) }
            assertEquals(token, prefs.getString(ReservationScheduler.ORDER_TOKEN, null))
            assertNull(prefs.getString(ReservationScheduler.IN_FLIGHT_TOKEN, null))
        }
        scheduler.cancelAll()
        store.saveSettings(enabled)
        registerDeliveredEarlyWake()
        logicalClock.now = target
        runBlocking { assertNull(scheduler.resumeInterruptedPreparation(activity)) }
        logicalClock.now = target.minusSeconds(WarmupAlarmPlanner.PREPARATION_MAX_MILLIS / 1_000L + 1L)
        runBlocking { assertNull(scheduler.resumeInterruptedPreparation(activity)) }
        logicalClock.now = target.minusSeconds(90)
        val protected = ExecutionRecord(day, 1, ExecutionStatus.NEEDS_CHECK, "SUBMIT", "unknown", submissionPossible = true)
        store.record(protected)
        runBlocking { assertNull(scheduler.resumeInterruptedPreparation(activity)) }
        assertTrue(dispatches.isEmpty())
        assertNull(prefs.getString(ReservationScheduler.IN_FLIGHT_TOKEN, null))
        assertEquals(listOf(protected), store.loadRecords())
    }

    @Test fun foregroundLostAfterClaimKeepsConsumedClaimAndNextDayWithoutDispatch() = withForeground { activity, scenario ->
        val token = registerDeliveredEarlyWake()
        beforeAlarmRegistration = {
            beforeAlarmRegistration = null
            scenario.moveToState(Lifecycle.State.CREATED)
        }
        runBlocking { scheduler.resumeInterruptedPreparation(activity) }
        flightTokens += token
        assertTrue(dispatches.isEmpty())
        assertEquals(token, prefs.getString(ReservationScheduler.IN_FLIGHT_TOKEN, null))
        assertEquals(target.toEpochMilli(), prefs.getLong(ReservationScheduler.CONSUMED_TARGET, 0L))
        assertFalse(ReservationScheduler.serviceClaim(context, token, AlarmDispatchKey(day, enabled.generation)))
        assertNextDayAlarm(day.plusDays(1))
    }

    @Test fun changedGenerationBetweenClaimAndDispatchIsRechecked() = withForeground { activity, _ ->
        val token = registerDeliveredEarlyWake()
        beforeAlarmRegistration = {
            beforeAlarmRegistration = null
            store.saveSettings(enabled.copy(generation = enabled.generation + 1))
        }
        runBlocking { scheduler.resumeInterruptedPreparation(activity) }
        flightTokens += token
        assertTrue(dispatches.isEmpty())
        assertEquals(target.toEpochMilli(), prefs.getLong(ReservationScheduler.CONSUMED_TARGET, 0L))
        assertFalse(ReservationScheduler.serviceClaim(context, token, AlarmDispatchKey(day, enabled.generation)))
        assertNull(consume(token))
    }

    @Test fun cancelledForegroundJobAfterClaimKeepsNextDayAndDoesNotDispatch() = withForeground { activity, _ ->
        val token = registerDeliveredEarlyWake()
        runBlocking {
            val operation = async(start = CoroutineStart.LAZY) { scheduler.resumeInterruptedPreparation(activity) }
            beforeAlarmRegistration = {
                beforeAlarmRegistration = null
                operation.cancel()
            }
            operation.start()
            runCatching { operation.await() }
            assertTrue(operation.isCancelled)
        }
        flightTokens += token
        assertTrue(dispatches.isEmpty())
        assertEquals(token, prefs.getString(ReservationScheduler.IN_FLIGHT_TOKEN, null))
        assertEquals(target.toEpochMilli(), prefs.getLong(ReservationScheduler.CONSUMED_TARGET, 0L))
        assertNextDayAlarm(day.plusDays(1))
        assertFalse(ReservationScheduler.serviceClaim(context, token, AlarmDispatchKey(day, enabled.generation)))
    }

    @Test fun existingDeadFutureClaimResumesOriginalTokenAndRetainsIndependentFutureAlarm() = withForeground { activity, _ ->
        val token = registerDeliveredEarlyWake()
        assertNotNull(consume(token))
        scheduler.reschedule()
        ProcessFlightExecution.registry.release(token)
        assertEquals(RegistrationCode.BLOCKED, scheduler.reschedule().code)
        runBlocking { scheduler.resumeInterruptedPreparation(activity) }
        assertEquals(1, dispatches.size)
        assertEquals(token, dispatches.single().getStringExtra(OrderService.EXTRA_FLIGHT_TOKEN))
        assertTrue(ReservationScheduler.serviceClaim(context, token, AlarmDispatchKey(day, enabled.generation)))
        assertNextDayAlarm(day.plusDays(1))
    }

    @Test fun realBootClearsDeadPriorProcessClaimAndUsesFutureColdTargetOnly() {
        val token = registerDeliveredEarlyWake()
        assertNotNull(consume(token))
        scheduler.reschedule()
        ProcessFlightExecution.registry.release(token)
        check(prefs.edit().putString(ReservationScheduler.IN_FLIGHT_PROCESS, "prior-boot-process").commit())
        assertEquals(RegistrationCode.REGISTERED, scheduler.reschedule(preserveDue = false).code)
        assertNull(prefs.getString(ReservationScheduler.IN_FLIGHT_TOKEN, null))
        assertEquals(0L, prefs.getLong(ReservationScheduler.CONSUMED_TARGET, 0L))
        assertEquals(target.toEpochMilli(), prefs.getLong(ReservationScheduler.ORDER_EPOCH, 0L))
        assertFalse(prefs.getBoolean(ReservationScheduler.ORDER_WARMUP, true))
        assertEquals(prefs.getLong(ReservationScheduler.TARGET_WALL_EPOCH, 0L), prefs.getLong(ReservationScheduler.WAKE_EPOCH, -1L))
        assertTrue(prefs.getLong(ReservationScheduler.WAKE_EPOCH, 0L) > System.currentTimeMillis())
    }

    private fun withForeground(block: (ComponentActivity, ActivityScenario<ComponentActivity>) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var activity: ComponentActivity
            scenario.onActivity { activity = it }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            block(activity, scenario)
        }
    }

    private fun cancelOsOrderAlarmOnly(token: String) {
        val intent = Intent().setComponent(ComponentName(InstrumentationRegistry.getInstrumentation().context.packageName,
            "${InstrumentationRegistry.getInstrumentation().context.packageName}.SchedulerNoopReceiver"))
            .setAction("$action.order").putExtra(ReservationScheduler.EXTRA_TOKEN, token)
        val pending = PendingIntent.getBroadcast(base, requestCode, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        assertNotNull("PI retained is not proof of an OS alarm", pending)
        base.getSystemService(AlarmManager::class.java).cancel(requireNotNull(pending))
    }

    private fun registerDeliveredEarlyWake(): String {
        logicalClock.now = target.minusSeconds(300)
        assertEquals(RegistrationCode.REGISTERED, scheduler.reschedule().code)
        val token = requireNotNull(prefs.getString(ReservationScheduler.ORDER_TOKEN, null))
        logicalClock.now = target.minusSeconds(90)
        check(prefs.edit().putLong(ReservationScheduler.WAKE_EPOCH, System.currentTimeMillis() - 1_000L)
            .putLong(ReservationScheduler.TARGET_WALL_EPOCH, System.currentTimeMillis() + 90_000L)
            .putBoolean(ReservationScheduler.ORDER_WARMUP, true).commit())
        return token
    }

    private fun consume(token: String): OrderReceiver.ConsumedAlarm? {
        flightTokens += token
        return OrderReceiver().consumeAlarm(context, Intent(ReservationScheduler.ACTION_ORDER)
            .putExtra(ReservationScheduler.EXTRA_TOKEN, token), false)
    }

    private fun assertNextDayAlarm(date: LocalDate) {
        assertEquals(date.atTime(6, 0).atZone(ReservationScheduler.ZONE).toInstant().toEpochMilli(),
            prefs.getLong(ReservationScheduler.ORDER_EPOCH, 0L))
        assertNotNull(prefs.getString(ReservationScheduler.ORDER_TOKEN, null))
        assertTrue(prefs.getLong(ReservationScheduler.WAKE_EPOCH, 0L) > System.currentTimeMillis())
        assertEquals("Exactly one real future ORDER alarm", 1, pendingOrderAlarmCount())
    }

    private fun pendingOrderAlarmCount(): Int {
        val dump = alarmDump()
        val history = dump.indexOf("App Alarm history:")
        check(history >= 0) { "Unsupported alarm dump format: current alarm boundary is missing" }
        // AlarmManager also dumps cancelled tags in Removal history; only pending stores precede this boundary.
        return dump.substring(0, history).lineSequence().count { it.trim() == "tag=*walarm*:$action.order" }
    }

    private fun alarmDump(): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys alarm"))
        .bufferedReader().use { it.readText() }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ReservationScheduler.ZONE
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private class SandboxContext(base: Context, private val metadataName: String) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun createDeviceProtectedStorageContext(): Context = SandboxContext(baseContext.createDeviceProtectedStorageContext(), metadataName)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences(if (name == "alarm_metadata") metadataName else name, mode)
    }
}
