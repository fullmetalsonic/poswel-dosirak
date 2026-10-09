package com.fullmetalsonic.dosirak.platform

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.data.AppStore
import com.fullmetalsonic.dosirak.domain.AppSettings
import com.fullmetalsonic.dosirak.domain.BackgroundCheckMode
import java.time.LocalTime
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Isolated database, metadata and fake alarms; this never obtains RuntimeProvider.
@RunWith(AndroidJUnit4::class)
class BackgroundCheckSchedulerTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var metadataName: String
    private lateinit var store: AppStore
    private lateinit var scheduler: BackgroundCheckScheduler
    private val alarms = FakeCheckAlarms()
    private var now = BackgroundCheckNow(1_800_000_000_000L, 100_000L)

    @Before fun prepare() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        databaseName = "background-check-test-$id.db"
        metadataName = "background-check-test-$id"
        store = AppStore(context, databaseName)
        scheduler = BackgroundCheckScheduler(context, store, metadataName, alarms) { now }
        store.saveSettings(AppSettings(backgroundCheckEnabled = true))
    }

    @After fun clean() {
        scheduler.cancel()
        store.close()
        context.deleteDatabase(databaseName)
        context.createDeviceProtectedStorageContext().deleteSharedPreferences(metadataName)
    }

    @Test fun sameRegistrationPreservesDeadlineAndForceUsesNewToken() {
        assertTrue(scheduler.reschedule().registered)
        val first = alarms.token
        val deadline = scheduler.status().nextCheckEpochMillis
        now = now.copy(epochMillis = now.epochMillis + 60_000L, elapsedMillis = now.elapsedMillis + 60_000L)
        assertTrue(scheduler.reschedule().registered)
        assertEquals(first, alarms.token)
        assertEquals(deadline, scheduler.status().nextCheckEpochMillis)
        assertEquals(1, alarms.registrations)
        scheduler.reschedule(force = true)
        assertNotEquals(first, alarms.token)
        assertEquals(2, alarms.registrations)
    }

    @Test fun enabledCheckNeverEnablesMasterOrChangesSettingsAndRecordsOnlyFixedSummary() {
        val original = store.loadSettings().copy(masterEnabled = false, generation = 42, liveBlockedReason = "차단 유지")
        store.saveSettings(original)
        scheduler.reschedule()
        val token = alarms.token
        advanceToDue()
        var calls = 0
        assertTrue(scheduler.dispatch(token) {
            calls++
            RegistrationResult(RegistrationCode.BLOCKED, "private-order-date-account-must-not-be-stored")
        })
        assertEquals(1, calls)
        assertEquals(original, store.loadSettings())
        assertEquals(now.epochMillis, scheduler.status().lastCheckEpochMillis)
        assertEquals("점검 완료 · 예약 실행 차단 유지", scheduler.status().summary)
        assertFalse(context.createDeviceProtectedStorageContext().getSharedPreferences(metadataName, Context.MODE_PRIVATE)
            .all.toString().contains("private-order-date-account"))
        assertTrue(scheduler.status().nextCheckEpochMillis!! > now.epochMillis)
    }

    @Test fun staleDuplicateAndEarlyTokensNeverDispatch() {
        scheduler.reschedule()
        val token = alarms.token
        var calls = 0
        val callback = { calls++; RegistrationResult(RegistrationCode.STOPPED, "") }
        assertFalse(scheduler.dispatch(token, callback))
        advanceToDue()
        assertFalse(scheduler.dispatch("stale", callback))
        assertTrue(scheduler.dispatch(token, callback))
        assertFalse(scheduler.dispatch(token, callback))
        assertEquals(1, calls)
    }

    @Test fun offCancelsFutureChecksAndRejectsAnAlreadyDeliveredToken() {
        scheduler.reschedule()
        val token = alarms.token
        advanceToDue()
        store.saveSettings(store.loadSettings().copy(backgroundCheckEnabled = false))
        var calls = 0
        assertFalse(scheduler.dispatch(token) { calls++; RegistrationResult(RegistrationCode.REGISTERED, "") })
        assertFalse(scheduler.reschedule().registered)
        assertEquals(0, calls)
        assertNull(scheduler.status().nextCheckEpochMillis)
        assertNull(scheduler.status().lastCheckEpochMillis)
    }

    @Test fun changedModeInvalidatesOldTokenAndSchedulesDailyKoreaTime() {
        scheduler.reschedule()
        val token = alarms.token
        store.saveSettings(store.loadSettings().copy(backgroundCheckMode = BackgroundCheckMode.DAILY,
            backgroundCheckTime = LocalTime.of(5, 50)))
        scheduler.reschedule()
        assertEquals(BackgroundCheckMode.DAILY, alarms.mode)
        assertNotEquals(token, alarms.token)
        var calls = 0
        assertFalse(scheduler.dispatch(token) { calls++; RegistrationResult(RegistrationCode.REGISTERED, "") })
        assertEquals(0, calls)
    }

    @Test fun localCheckFailureIsSanitizedAndNextCheckIsStillRegistered() {
        scheduler.reschedule()
        val token = alarms.token
        advanceToDue()
        assertTrue(scheduler.dispatch(token) { throw IllegalStateException("private failure") })
        assertEquals("점검 실패 · 예약 등록 상태를 확인하세요.", scheduler.status().summary)
        assertTrue(scheduler.status().nextCheckEpochMillis!! > now.epochMillis)
    }

    @Test fun disablingDuringLocalCheckDoesNotWriteAStaleSuccessOrScheduleAgain() {
        scheduler.reschedule()
        val token = alarms.token
        advanceToDue()
        assertTrue(scheduler.dispatch(token) {
            store.saveSettings(store.loadSettings().copy(backgroundCheckEnabled = false))
            scheduler.reschedule()
            RegistrationResult(RegistrationCode.REGISTERED, "")
        })
        assertNull(scheduler.status().lastCheckEpochMillis)
        assertNull(scheduler.status().nextCheckEpochMillis)
        assertFalse(store.loadSettings().backgroundCheckEnabled)
    }

    @Test fun failedAlarmRegistrationDoesNotLeaveAnActiveToken() {
        alarms.failRegistration = true
        assertFalse(scheduler.reschedule().registered)
        assertNull(scheduler.status().nextCheckEpochMillis)
        assertNull(scheduler.status().lastCheckEpochMillis)
    }

    @Test fun manualCheckCallsRegistrationOnceAndKeepsSettingsAndPendingPeriodicToken() {
        val original = store.loadSettings().copy(masterEnabled = true, generation = 42,
            accountGeneration = 7, liveBlockedReason = "차단 유지")
        store.saveSettings(original)
        scheduler.reschedule()
        val token = alarms.token
        val deadline = scheduler.status().nextCheckEpochMillis
        val registrations = alarms.registrations
        var calls = 0
        val result = scheduler.checkNow {
            calls++
            RegistrationResult(RegistrationCode.BLOCKED, "private-order-account-date")
        }
        assertEquals(1, calls)
        assertEquals(RegistrationCode.BLOCKED, result.code)
        assertEquals("점검 완료 · 예약 실행 차단 유지", result.message)
        assertEquals(original, store.loadSettings())
        assertEquals(token, alarms.token)
        assertEquals(deadline, scheduler.status().nextCheckEpochMillis)
        assertEquals(registrations, alarms.registrations)
        assertEquals(now.epochMillis, scheduler.status().lastCheckEpochMillis)
        assertFalse(context.createDeviceProtectedStorageContext().getSharedPreferences(metadataName, Context.MODE_PRIVATE)
            .all.toString().contains("private-order-account-date"))
        advanceToDue()
        assertTrue(scheduler.dispatch(token) { RegistrationResult(RegistrationCode.BLOCKED, "") })
    }

    @Test fun preparingWorkIsNotReportedAsAnAlarmWaitingToFire() {
        val original = store.loadSettings()
        val result = scheduler.checkNow { RegistrationResult(RegistrationCode.IN_FLIGHT, "") }
        assertEquals(RegistrationCode.IN_FLIGHT, result.code)
        assertEquals("점검 완료 · 신청 준비/처리 중", scheduler.status().summary)
        assertEquals(original, store.loadSettings())
        assertEquals(0, alarms.registrations)
    }

    @Test fun manualCheckWhilePeriodicIsOffNeverEnablesEitherAutomaticSwitch() {
        val original = AppSettings(backgroundCheckEnabled = false, masterEnabled = false,
            generation = 13, accountGeneration = 8, liveBlockedReason = "차단 유지")
        store.saveSettings(original)
        var calls = 0
        val result = scheduler.checkNow { calls++; RegistrationResult(RegistrationCode.STOPPED, "") }
        assertEquals(1, calls)
        assertEquals(RegistrationCode.STOPPED, result.code)
        assertEquals(original, store.loadSettings())
        assertEquals(0, alarms.registrations)
        assertNull(alarms.token)
        assertNull(scheduler.status().nextCheckEpochMillis)
        assertEquals(now.epochMillis, scheduler.status().lastCheckEpochMillis)
        assertEquals("점검 완료 · 자동주문 비활성", scheduler.status().summary)
    }

    @Test fun manualFailureRecordsSafeSummaryWithoutMovingFuturePeriodicCheck() {
        scheduler.reschedule()
        val token = alarms.token
        val deadline = scheduler.status().nextCheckEpochMillis
        var calls = 0
        val result = scheduler.checkNow { calls++; throw IllegalStateException("private-account-error") }
        assertEquals(1, calls)
        assertEquals(RegistrationCode.FAILED, result.code)
        assertEquals("점검 실패 · 예약 등록 상태를 확인하세요.", scheduler.status().summary)
        assertEquals(now.epochMillis, scheduler.status().lastCheckEpochMillis)
        assertEquals(token, alarms.token)
        assertEquals(deadline, scheduler.status().nextCheckEpochMillis)
        assertEquals(1, alarms.registrations)
        assertFalse(context.createDeviceProtectedStorageContext().getSharedPreferences(metadataName, Context.MODE_PRIVATE)
            .all.toString().contains("private-account-error"))
    }

    @Test fun repeatedManualChecksUpdateLastTimeWithoutDelayingPeriodicDeadline() {
        scheduler.reschedule()
        val deadline = scheduler.status().nextCheckEpochMillis
        val token = alarms.token
        val callback = { RegistrationResult(RegistrationCode.REGISTERED, "") }
        scheduler.checkNow(callback)
        val firstCheck = scheduler.status().lastCheckEpochMillis
        now = now.copy(epochMillis = now.epochMillis + 60_000L, elapsedMillis = now.elapsedMillis + 60_000L)
        scheduler.checkNow(callback)
        assertTrue(scheduler.status().lastCheckEpochMillis!! > firstCheck!!)
        assertEquals(now.epochMillis, scheduler.status().lastCheckEpochMillis)
        assertEquals(deadline, scheduler.status().nextCheckEpochMillis)
        assertEquals(token, alarms.token)
        assertEquals(1, alarms.registrations)
    }

    private fun advanceToDue() { now = BackgroundCheckNow(now.epochMillis + 3_600_000L, now.elapsedMillis + 3_600_000L) }

    private class FakeCheckAlarms : BackgroundCheckAlarmBackend {
        var token: String? = null
        var mode: BackgroundCheckMode? = null
        var registrations = 0
        var failRegistration = false
        override fun schedule(mode: BackgroundCheckMode, next: BackgroundCheckNow, token: String) {
            if (failRegistration) throw SecurityException("fixture registration refused")
            this.mode = mode
            this.token = token
            registrations++
        }
        override fun cancel() { token = null }
    }
}
