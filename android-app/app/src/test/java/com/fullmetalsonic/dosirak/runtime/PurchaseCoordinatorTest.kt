package com.fullmetalsonic.dosirak.runtime

import com.fullmetalsonic.dosirak.domain.*
import com.fullmetalsonic.dosirak.platform.AlarmPlanSelector
import com.fullmetalsonic.dosirak.site.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PurchaseCoordinatorTest {
    private val date = LocalDate.of(2026, 10, 9)
    private val zone = ZoneId.of("Asia/Seoul")

    private inner class MemoryStorage : PurchaseStorage {
        var value = AppSettings(masterEnabled = true, limitEnabled = true, unitLimit = 5000, orderLimit = 10000,
            liveScope = LiveScope.RECURRING, displayPriceRiskAccepted = true, accountGeneration = 4, generation = 8)
        var exceptions = mapOf(date to DateOverride(date, DatePolicy.MANUAL, 2, LocalTime.of(6, 0)))
        var account: Credentials? = Credentials("account", "secret")
        val ledger = mutableListOf<ExecutionRecord>()
        val writes = mutableListOf<ExecutionRecord>()
        var failingStage: String? = null
        var beforeUpdate: () -> Unit = {}
        override fun settings() = value
        override fun overrides() = exceptions
        override fun records() = ledger.toList()
        override fun saveSettings(settings: AppSettings) { value = settings }
        override fun updateSettings(change: (AppSettings) -> AppSettings) {
            beforeUpdate()
            value = change(value)
        }
        override fun record(record: ExecutionRecord) {
            if (record.stage == failingStage) throw IllegalStateException("simulated durable write failure")
            ledger.removeAll { it.date == record.date }; ledger.add(record); writes.add(record)
        }
        override fun credentials(): Credentials? = account
    }

    private inner class FakeGateway(private val storage: MemoryStorage) : SiteGateway {
        var creates = 0
        var submits = 0
        val checkoutDates = mutableListOf<LocalDate>()
        val submittedDates = mutableListOf<LocalDate>()
        var reads = 0
        var identityChecks = 0
        var sessionAccount = "account"
        var identityFailure: SiteException? = null
        val logins = mutableListOf<Credentials?>()
        var expired = false
        var credentialSessionFailure: SiteException? = null
        var beforeSession: () -> Unit = {}
        var beforeCheckout: () -> Unit = {}
        var afterSubmit: () -> Unit = {}
        var createFailure: SiteException? = null
        var submitFailure: SiteException? = null
        var timeProbes = 0
        var menus = 0
        val events = mutableListOf<String>()
        var serverTime: () -> ServerTimeSample = { throw SiteException("TIME_UNAVAILABLE", "unavailable") }
        var beforeMenu: () -> Unit = {}
        var beforeTempMutation: () -> Unit = {}
        var beforeFinalMutation: () -> Unit = {}
        var menuFactory: ((OrderPlan, Long, Long) -> MenuSnapshot?)? = null
        var receivedMenu: MenuSnapshot? = null
        var submittedCheckout: CheckoutSnapshot? = null
        var checkout = CheckoutSnapshot(date, 2, 5000, 10000, "account", "temp", emptyMap())
        var history: () -> List<SiteOrder> = { if (submits > 0) listOf(completed()) else emptyList() }
        var historyByDate: ((LocalDate) -> List<SiteOrder>)? = null
        override fun ensureSession(credentials: Credentials?) {
            events.add("session")
            beforeSession()
            logins.add(credentials)
            if (expired && credentials == null) throw SiteException("LOGIN_REQUIRED", "expired")
            if (credentials != null) credentialSessionFailure?.let { throw it }
            if (credentials != null) expired = false
        }
        override fun verifyAccount(expectedAccountId: String) {
            events.add("identity")
            identityChecks++
            identityFailure?.let { throw it }
            if (sessionAccount != expectedAccountId) throw SiteException("ACCOUNT_MISMATCH", "different account")
        }
        override fun readOrders(date: LocalDate): List<SiteOrder> { events.add("history"); reads++; return historyByDate?.invoke(date) ?: history() }
        override fun probeServerTime(): ServerTimeSample { events.add("time"); timeProbes++; return serverTime() }
        override fun loadMenu(plan: OrderPlan, generation: Long, accountGeneration: Long): MenuSnapshot? {
            events.add("menu")
            menus++; beforeMenu()
            return menuFactory?.invoke(plan, generation, accountGeneration)
        }
        override fun createCheckout(plan: OrderPlan, menu: MenuSnapshot?, beforeMutation: () -> Unit): CheckoutSnapshot {
            receivedMenu = menu
            beforeTempMutation(); beforeMutation()
            return createCheckout(plan)
        }
        override fun createCheckout(plan: OrderPlan): CheckoutSnapshot {
            events.add("temp")
            creates++
            checkoutDates.add(plan.date)
            val intent = storage.ledger.single { it.date == plan.date }
            assertTrue(intent.submissionPossible)
            assertEquals("TEMP_INTENT", intent.stage)
            beforeCheckout()
            createFailure?.let { throw it }
            return checkout
        }
        override fun submit(checkout: CheckoutSnapshot): SubmitReceipt {
            submittedCheckout = checkout
            events.add("final")
            submits++
            submittedDates.add(checkout.date)
            val intent = storage.ledger.single { it.date == checkout.date }
            assertTrue(intent.submissionPossible)
            assertEquals("SUBMIT_INTENT", intent.stage)
            afterSubmit()
            submitFailure?.let { throw it }
            return SubmitReceipt("ok", 200)
        }
        override fun submit(checkout: CheckoutSnapshot, beforeMutation: () -> Unit): SubmitReceipt {
            beforeFinalMutation(); beforeMutation()
            return submit(checkout)
        }
    }

    private inner class TestClock(var value: Instant = Instant.parse("2026-10-08T21:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = this@PurchaseCoordinatorTest.zone
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(value, zone)
        override fun instant() = value
    }

    private inner class Setup {
        val storage = MemoryStorage()
        val gateway = FakeGateway(storage)
        val clock = TestClock()
        val waits = mutableListOf<Long>()
        val coordinator = PurchaseCoordinator(storage, gateway, clock, wait = { waits.add(it) })
    }

    private inner class ServerSetup(serverOffset: Long = 0, phoneOffset: Long = 0) {
        val storage = MemoryStorage()
        val gateway = FakeGateway(storage)
        var elapsed = 10_000L
        val wall = TestClock(Instant.parse("2026-10-08T21:00:00Z").plusMillis(phoneOffset))
        var serverEpoch = Instant.parse("2026-10-08T21:00:00Z").toEpochMilli() + serverOffset
        val clock = ServerOrderClock(wall) { elapsed }
        val waits = mutableListOf<Long>()
        var afterWait: () -> Unit = {}
        val coordinator = PurchaseCoordinator(storage, gateway, clock, wait = {
            if (gateway.creates == 0) assertTrue(it <= 500)
            waits.add(it); advance(it); afterWait()
        })
        init { gateway.serverTime = { ServerTimeSample(serverEpoch, elapsed, elapsed, wall.millis()) } }
        fun advance(millis: Long) { elapsed += millis; serverEpoch += millis; wall.value = wall.value.plusMillis(millis) }
    }

    private fun completed(quantity: Int? = 2, total: Long? = 10000, status: String = "주문완료") =
        SiteOrder("order", date, quantity, status, total, "menu")

    private fun menu(plan: OrderPlan, generation: Long, accountGeneration: Long): MenuSnapshot {
        val snapshotGeneration = generation
        val snapshotAccountGeneration = accountGeneration
        return object : MenuSnapshot {
            override val date = plan.date
            override val quantity = plan.quantity
            override val generation = snapshotGeneration
            override val accountGeneration = snapshotAccountGeneration
        }
    }

    @Test fun recurringConsentAllowsFirstPurchaseAndSubmissionIsExactlyOnce() = runBlocking {
        val s = Setup()
        assertNull(s.storage.value.verifiedLiveAccountGeneration)
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date).status)
        assertEquals(4L, s.storage.value.verifiedLiveAccountGeneration)
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date).status)
        assertEquals(1, s.gateway.creates); assertEquals(1, s.gateway.submits)
        assertNull(s.gateway.logins.first())
    }

    @Test fun numericStoredAccountAcceptsOnlyExactOrFixedPcCheckoutAndPreservesRawForm() = runBlocking {
        listOf("123456" to "123456", "123456" to "PC123456", "001234" to "PC001234").forEach { (expected, observed) ->
            val s = Setup()
            s.storage.account = Credentials(expected, "secret")
            s.gateway.sessionAccount = expected
            val checkout = s.gateway.checkout.copy(accountId = observed, fields = mapOf("od_jikbun" to listOf(observed)))
            s.gateway.checkout = checkout
            assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date).status)
            assertSame(checkout, s.gateway.submittedCheckout)
            assertEquals(observed, s.gateway.submittedCheckout?.accountId)
            assertEquals(listOf(observed), s.gateway.submittedCheckout?.fields?.get("od_jikbun"))
            assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date).status)
            assertEquals(1, s.gateway.submits)
        }
    }

    @Test fun mismatchedOrMalformedPrefixedCheckoutCannotReachFinalSubmit() = runBlocking {
        listOf("PC654321", "pc123456", "XX123456", "PC123456x", "PC 123456", "PC12345",
            "PC1234567", "PCPC123456", "PC123456 ", "PC\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16").forEach { observed ->
            val s = Setup()
            s.storage.account = Credentials("123456", "secret")
            s.gateway.sessionAccount = "123456"
            s.gateway.checkout = s.gateway.checkout.copy(accountId = observed)
            val result = s.coordinator.execute(date)
            assertEquals(ExecutionStatus.NEEDS_CHECK, result.status)
            assertEquals("CHECKOUT_BLOCKED", result.stage)
            assertEquals(0, s.gateway.submits)
        }
    }

    @Test fun concurrentInstancesShareExecutionLock() = runBlocking {
        val s = Setup()
        val other = PurchaseCoordinator(s.storage, s.gateway, s.clock, wait = {})
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        s.gateway.beforeCheckout = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val first = async(Dispatchers.Default) { s.coordinator.execute(date) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) { secondStarted.countDown(); other.execute(date) }
        assertTrue(secondStarted.await(3, TimeUnit.SECONDS))
        release.countDown()
        first.await(); second.await()
        assertEquals(1, s.gateway.submits)
    }

    @Test fun priorIntentAndEmptyHistoryAreQueryOnlyEvenAfterClosing() = runBlocking {
        val s = Setup()
        s.storage.record(ExecutionRecord(date, 2, ExecutionStatus.RUNNING, "TEMP_INTENT", "intent", submissionPossible = true, accountGeneration = 4))
        s.clock.value = Instant.parse("2026-10-08T23:30:00Z")
        assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.execute(date).status)
        assertTrue(s.storage.ledger.single().submissionPossible)
        assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
    }

    @Test fun recreatedCoordinatorCannotReplayLostFinalResponse() = runBlocking {
        val s = Setup()
        s.gateway.submitFailure = SiteException("LOST", "lost", true)
        s.gateway.history = { emptyList() }
        assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.execute(date).status)
        val recreated = PurchaseCoordinator(s.storage, s.gateway, s.clock, wait = {})
        recreated.execute(date)
        assertEquals(1, s.gateway.submits); assertEquals(1, s.gateway.creates)
        assertEquals(listOf(3000L, 3000L), s.waits)
    }

    @Test fun processCrashLeavesIntentBeforeAnyMutatingCall() = runBlocking {
        val s = Setup()
        s.gateway.beforeCheckout = { throw AssertionError("simulated process crash") }
        try { s.coordinator.execute(date); fail("Expected crash") } catch (_: AssertionError) { }
        assertTrue(s.storage.ledger.single().submissionPossible)
        s.gateway.beforeCheckout = {}
        PurchaseCoordinator(s.storage, s.gateway, s.clock, wait = {}).execute(date)
        assertEquals(1, s.gateway.creates); assertEquals(0, s.gateway.submits)
    }

    @Test fun responseLossWithMatchingHistoryIsSuccessWithoutRepost() = runBlocking {
        val s = Setup()
        s.gateway.submitFailure = SiteException("LOST", "lost", true)
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date).status)
        assertEquals(1, s.gateway.submits)
        assertEquals(4L, s.storage.value.verifiedLiveAccountGeneration)
    }

    @Test fun existingCompleteOrderDoesNotBecomeLiveProof() = runBlocking {
        val s = Setup()
        s.gateway.history = { listOf(completed()) }
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date).status)
        assertNull(s.storage.value.verifiedLiveAccountGeneration)
        assertEquals(0, s.gateway.creates)
    }

    @Test fun canceledRowsAreExcludedFromActiveOrderConflict() = runBlocking {
        val s = Setup()
        s.gateway.history = { listOf(completed(null, null, "주문취소")) + if (s.gateway.submits > 0) listOf(completed()) else emptyList() }
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date).status)
        assertEquals(1, s.gateway.submits)
    }

    @Test fun knownCompletedOrderLaterCanceledNeverReorders() = runBlocking {
        val s = Setup()
        s.coordinator.execute(date)
        s.gateway.history = { listOf(completed(null, null, "주문취소")) }
        assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.execute(date).status)
        s.coordinator.execute(date)
        assertEquals(1, s.gateway.submits)
    }

    @Test fun conflictingMultipleUnknownQuantityAndUnreadableAmountStopBeforeTemp() = runBlocking {
        for (orders in listOf(listOf(completed(), completed()), listOf(completed(1)),
            listOf(completed(null)), listOf(completed(total = null)), listOf(completed(status = "처리중")))) {
            val s = Setup(); s.gateway.history = { orders }
            assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.execute(date).status)
            assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
        }
    }

    @Test fun accountMismatchAndCheckoutAmountLimitsBlockFinal() = runBlocking {
        for (change in listOf<(CheckoutSnapshot) -> CheckoutSnapshot>(
            { it.copy(accountId = "other") }, { it.copy(date = date.plusDays(1)) }, { it.copy(quantity = 1) },
            { it.copy(total = 0) }, { it.copy(unitPrice = 6000, total = 12000) }, { it.copy(total = 9000) })) {
            val s = Setup(); s.gateway.checkout = change(s.gateway.checkout)
            assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.execute(date).status)
            assertEquals(0, s.gateway.submits)
            assertTrue(s.storage.ledger.single().submissionPossible)
        }
    }

    @Test fun changedGenerationAccountMasterPlanAndScopeBlockFinal() = runBlocking {
        for (change in listOf<(Setup) -> Unit>(
            { it.storage.value = it.storage.value.copy(generation = 9) },
            { it.storage.value = it.storage.value.copy(accountGeneration = 5) },
            { it.storage.value = it.storage.value.copy(masterEnabled = false) },
            { it.storage.value = it.storage.value.copy(liveScope = LiveScope.NONE) },
            { it.storage.value = it.storage.value.copy(displayPriceRiskAccepted = false) },
            { it.storage.account = Credentials("other", "secret") },
            { it.storage.exceptions = mapOf(date to DateOverride(date, DatePolicy.EXCLUDE)) })) {
            val s = Setup(); s.gateway.beforeCheckout = { change(s) }
            assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.execute(date).status)
            assertEquals(0, s.gateway.submits)
        }
    }

    @Test fun manualPurchaseRequiresItsOwnRiskConsentWithoutMasterSwitch() = runBlocking {
        val s = Setup(); s.storage.value = s.storage.value.copy(masterEnabled = false, liveScope = LiveScope.NONE)
        assertEquals(ExecutionStatus.SKIPPED, s.coordinator.execute(date, manual = true).status)
        assertEquals(0, s.gateway.creates)
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date, manual = true, acceptedPriceRisk = true).status)
    }

    @Test fun scheduledMasterRiskAndScopeAreMandatory() = runBlocking {
        for (change in listOf<(AppSettings) -> AppSettings>(
            { it.copy(masterEnabled = false) }, { it.copy(displayPriceRiskAccepted = false) },
            { it.copy(liveScope = LiveScope.NONE) }, { it.copy(liveScope = LiveScope.SINGLE_DATE, liveTestDate = date.plusDays(1)) })) {
            val s = Setup(); s.storage.value = change(s.storage.value)
            assertEquals(ExecutionStatus.SKIPPED, s.coordinator.execute(date).status)
            assertEquals(0, s.gateway.creates)
        }
    }

    @Test fun kstTodayPreSixPlanTimeAndEightClosingAreEnforced() = runBlocking {
        for (instant in listOf("2026-10-08T20:59:59Z", "2026-10-08T23:00:00Z", "2026-10-09T21:00:00Z")) {
            val s = Setup(); s.clock.value = Instant.parse(instant)
            assertEquals(ExecutionStatus.SKIPPED, s.coordinator.execute(date).status)
            assertEquals(0, s.gateway.creates)
        }
        val s = Setup(); s.storage.exceptions = mapOf(date to DateOverride(date, DatePolicy.MANUAL, 2, LocalTime.of(7, 0)))
        assertEquals(ExecutionStatus.SKIPPED, s.coordinator.execute(date).status)
        assertEquals(0, s.gateway.creates)
    }

    @Test fun closingDuringCheckoutStopsFinal() = runBlocking {
        val s = Setup(); s.gateway.beforeCheckout = { s.clock.value = Instant.parse("2026-10-08T23:00:00Z") }
        assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.execute(date).status)
        assertEquals(0, s.gateway.submits)
    }

    @Test fun tempPostAmbiguityNeverRetriesRegardlessOfRetrySettings() = runBlocking {
        val s = Setup(); s.storage.value = s.storage.value.copy(retryCount = 10)
        s.gateway.createFailure = SiteException("TEMP_FAILED", "failure", true)
        assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.execute(date).status)
        s.coordinator.execute(date)
        assertEquals(1, s.gateway.creates); assertEquals(0, s.gateway.submits); assertTrue(s.waits.isEmpty())
    }

    @Test fun positivelySafePreTempFailureCanRetryWithConfiguredDelay() = runBlocking {
        val s = Setup(); s.storage.value = s.storage.value.copy(retryCount = 2, retryIntervalSeconds = 7)
        s.gateway.createFailure = SiteException("SOLD_OUT", "before post", false)
        assertEquals(ExecutionStatus.FAILED, s.coordinator.execute(date).status)
        assertEquals(3, s.gateway.creates); assertEquals(0, s.gateway.submits)
        assertEquals(listOf(7000L, 7000L), s.waits)
        assertFalse(s.storage.ledger.single().submissionPossible)
    }

    @Test fun cookiesAreTriedBeforeOptionalCredentialFallback() = runBlocking {
        val s = Setup(); s.gateway.expired = true
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date).status)
        assertNull(s.gateway.logins[0]); assertEquals("account", s.gateway.logins[1]?.userId)
        val disabled = Setup(); disabled.gateway.expired = true
        disabled.storage.value = disabled.storage.value.copy(useStoredCredentials = false)
        assertEquals(ExecutionStatus.FAILED, disabled.coordinator.execute(date).status)
        assertEquals(listOf<Credentials?>(null), disabled.gateway.logins)
        assertEquals(0, disabled.gateway.creates)
    }

    @Test fun savedIdentityRequiredEvenWhenCookieSessionWorks() = runBlocking {
        val s = Setup(); s.storage.account = null
        assertEquals(ExecutionStatus.FAILED, s.coordinator.execute(date).status)
        assertEquals(0, s.gateway.creates)
    }

    @Test fun mismatchedFinalAmountBlocksFuturePurchasesWithoutCancel() = runBlocking {
        val s = Setup(); s.gateway.history = { if (s.gateway.submits > 0) listOf(completed(total = 12000)) else emptyList() }
        assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.execute(date).status)
        assertNotNull(s.storage.value.liveBlockedReason)
        assertNull(s.storage.value.verifiedLiveAccountGeneration)
        s.coordinator.execute(date)
        assertEquals(1, s.gateway.submits)
    }

    @Test fun accountChangeWithPriorIntentDoesNotQueryNewAccountAsOldResult() = runBlocking {
        val s = Setup()
        s.storage.record(ExecutionRecord(date, 2, ExecutionStatus.RUNNING, "SUBMIT_INTENT", "intent", amount = 10000,
            submissionPossible = true, accountGeneration = 3))
        assertEquals("ACCOUNT_CHANGED", s.coordinator.execute(date).stage)
        assertEquals(0, s.gateway.reads); assertEquals(0, s.gateway.creates)
    }

    @Test fun competingOrderDiscoveredBeforeFinalStopsWithoutProof() = runBlocking {
        val s = Setup(); s.gateway.history = { if (s.gateway.creates > 0) listOf(completed()) else emptyList() }
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date).status)
        assertEquals(0, s.gateway.submits)
        assertNull(s.storage.value.verifiedLiveAccountGeneration)
    }

    @Test fun delayedHistoryUsesTwoReadRetriesAndNeverAdditionalSubmit() = runBlocking {
        val s = Setup(); s.gateway.history = { if (s.gateway.reads >= 5) listOf(completed()) else emptyList() }
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date).status)
        assertEquals(1, s.gateway.submits); assertEquals(5, s.gateway.reads)
        assertEquals(listOf(3000L, 3000L), s.waits)
    }

    @Test fun finalIntentProcessCrashCannotReplayAfterRestart() = runBlocking {
        val s = Setup(); s.gateway.afterSubmit = { throw AssertionError("simulated crash") }
        try { s.coordinator.execute(date); fail("Expected crash") } catch (_: AssertionError) { }
        assertEquals("SUBMIT_INTENT", s.storage.ledger.single().stage)
        s.gateway.history = { emptyList() }; s.gateway.afterSubmit = {}
        PurchaseCoordinator(s.storage, s.gateway, s.clock, wait = {}).execute(date)
        assertEquals(1, s.gateway.submits)
        assertTrue(s.storage.ledger.single().submissionPossible)
    }

    @Test fun authenticationContractBlockPreservesMasterIntent() = runBlocking {
        val s = Setup(); s.gateway.history = { throw SiteException("HISTORY_CONTRACT", "changed") }
        assertEquals(ExecutionStatus.FAILED, s.coordinator.execute(date).status)
        assertTrue(s.storage.value.masterEnabled); assertNotNull(s.storage.value.liveBlockedReason)
        assertEquals(0, s.gateway.creates)
    }

    @Test fun transientNetworkQueryFailureDoesNotBlockFuturePlans() = runBlocking {
        val s = Setup(); s.storage.value = s.storage.value.copy(retryCount = 1)
        s.gateway.history = { throw SiteException("NETWORK", "offline") }
        assertEquals(ExecutionStatus.FAILED, s.coordinator.execute(date).status)
        assertTrue(s.storage.value.masterEnabled); assertNull(s.storage.value.liveBlockedReason)
        assertEquals(2, s.gateway.reads); assertEquals(0, s.gateway.creates)
    }

    @Test fun finalPrePostRejectionDoesNotProvideLiveProof() = runBlocking {
        val s = Setup(); s.gateway.submitFailure = SiteException("DATE_NOT_TODAY", "before post", false)
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date).status)
        assertNull(s.storage.value.verifiedLiveAccountGeneration)
    }

    @Test fun wrongCookieAccountCannotConfirmExistingOrderOrCreateCheckout() = runBlocking {
        val s = Setup(); s.gateway.sessionAccount = "other"
        s.gateway.history = { listOf(completed()) }
        assertEquals(ExecutionStatus.FAILED, s.coordinator.execute(date).status)
        assertEquals(0, s.gateway.reads); assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
        assertNotNull(s.storage.value.liveBlockedReason); assertTrue(s.storage.value.masterEnabled)
        assertTrue(s.storage.value.liveBlockedReason!!.startsWith("ACCOUNT_MISMATCH:"))
    }

    @Test fun refreshVerifiesCookieAccountEvenWithCredentialFallbackDisabled() = runBlocking {
        val s = Setup(); s.storage.value = s.storage.value.copy(useStoredCredentials = false)
        s.gateway.sessionAccount = "other"; s.gateway.history = { listOf(completed()) }
        assertEquals(ExecutionStatus.FAILED, s.coordinator.refreshOrders(date).status)
        assertEquals(0, s.gateway.reads); assertEquals(1, s.gateway.identityChecks)
        assertNotNull(s.storage.value.liveBlockedReason)
        assertTrue(s.storage.ledger.isEmpty())
        assertEquals(listOf<Credentials?>(null), s.gateway.logins)
    }

    @Test fun refreshRequiresSavedAccountIdentityBeforeQuery() = runBlocking {
        val s = Setup(); s.storage.account = null; s.gateway.history = { listOf(completed()) }
        assertEquals(ExecutionStatus.FAILED, s.coordinator.refreshOrders(date).status)
        assertEquals(0, s.gateway.reads); assertEquals(0, s.gateway.identityChecks)
        assertTrue(s.storage.ledger.isEmpty())
    }

    @Test fun sessionAccountChangingAfterTempBlocksFinalAndFuturePurchases() = runBlocking {
        val s = Setup(); s.gateway.beforeCheckout = { s.gateway.sessionAccount = "other" }
        assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.execute(date).status)
        assertEquals(0, s.gateway.submits)
        assertNotNull(s.storage.value.liveBlockedReason)
        assertTrue(s.storage.ledger.single().submissionPossible)
    }

    @Test fun adapterWithoutIdentityContractFailsClosedAndPreservesReasonCode() = runBlocking {
        val s = Setup(); s.gateway.identityFailure = SiteException("ACCOUNT_UNVERIFIED", "unsupported identity")
        assertEquals(ExecutionStatus.FAILED, s.coordinator.execute(date).status)
        assertEquals(0, s.gateway.reads); assertEquals(0, s.gateway.creates)
        assertTrue(s.storage.value.liveBlockedReason!!.startsWith("ACCOUNT_UNVERIFIED:"))
    }

    @Test fun tempIntentPersistenceFailureStopsBeforeAnyMutatingPost() = runBlocking {
        val s = Setup(); s.storage.failingStage = "TEMP_INTENT"
        try { s.coordinator.execute(date); fail("Expected durable write failure") }
        catch (failure: IllegalStateException) { assertEquals("simulated durable write failure", failure.message) }
        assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
        assertTrue(s.storage.ledger.isEmpty())
    }

    @Test fun finalIntentPersistenceFailurePreservesTempIntentAndStopsFinalPost() = runBlocking {
        val s = Setup(); s.storage.failingStage = "SUBMIT_INTENT"
        try { s.coordinator.execute(date); fail("Expected durable write failure") }
        catch (failure: IllegalStateException) { assertEquals("simulated durable write failure", failure.message) }
        assertEquals(1, s.gateway.creates); assertEquals(0, s.gateway.submits)
        assertEquals("TEMP_INTENT", s.storage.ledger.single().stage)
        assertTrue(s.storage.ledger.single().submissionPossible)
        s.storage.failingStage = null
        PurchaseCoordinator(s.storage, s.gateway, s.clock, wait = {}).execute(date)
        assertEquals(1, s.gateway.creates); assertEquals(0, s.gateway.submits)
        assertTrue(s.storage.ledger.single().submissionPossible)
    }

    @Test fun masterDisabledDuringSafeRetryDelayPreventsNextCheckout() = runBlocking {
        val s = Setup(); s.storage.value = s.storage.value.copy(retryCount = 1)
        s.gateway.createFailure = SiteException("ORDER_UNAVAILABLE", "safe pre-post failure", false)
        val coordinator = PurchaseCoordinator(s.storage, s.gateway, s.clock, wait = {
            assertEquals(3000L, it)
            s.storage.value = s.storage.value.copy(masterEnabled = false)
        })
        assertEquals(ExecutionStatus.SKIPPED, coordinator.execute(date).status)
        assertEquals(1, s.gateway.creates); assertEquals(0, s.gateway.submits)
        assertFalse(s.storage.ledger.single().submissionPossible)
        assertEquals(DatePolicy.MANUAL, s.storage.exceptions[date]?.policy)
    }

    @Test fun proofMetadataUsesLatestSettingsAndRechecksGenerationInsideUpdate() = runBlocking {
        for (accountChanged in listOf(false, true)) {
            val s = Setup()
            s.storage.beforeUpdate = {
                s.storage.value = s.storage.value.copy(masterEnabled = false,
                    generation = if (accountChanged) 8 else 9, accountGeneration = if (accountChanged) 5 else 4)
            }
            assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date).status)
            assertFalse(s.storage.value.masterEnabled)
            assertEquals(if (accountChanged) 8L else 9L, s.storage.value.generation)
            assertEquals(if (accountChanged) 5L else 4L, s.storage.value.accountGeneration)
            assertNull(s.storage.value.verifiedLiveAccountGeneration)
        }
    }

    @Test fun authenticationBlockMetadataPreservesConcurrentUserStopAndGeneration() = runBlocking {
        val s = Setup(); s.gateway.sessionAccount = "other"
        s.storage.beforeUpdate = { s.storage.value = s.storage.value.copy(masterEnabled = false, generation = 9) }
        assertEquals(ExecutionStatus.FAILED, s.coordinator.execute(date).status)
        assertFalse(s.storage.value.masterEnabled); assertEquals(9L, s.storage.value.generation)
        assertTrue(s.storage.value.liveBlockedReason!!.startsWith("ACCOUNT_MISMATCH:"))
    }

    @Test fun amountMismatchMetadataPreservesConcurrentUserStopAndGeneration() = runBlocking {
        val s = Setup(); s.gateway.history = { if (s.gateway.submits > 0) listOf(completed(total = 12000)) else emptyList() }
        s.storage.beforeUpdate = { s.storage.value = s.storage.value.copy(masterEnabled = false, generation = 9) }
        assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.execute(date).status)
        assertFalse(s.storage.value.masterEnabled); assertEquals(9L, s.storage.value.generation)
        assertNotNull(s.storage.value.liveBlockedReason)
    }

    @Test fun recurringConsentPersistsAcrossTwoPlannedDatesWithoutDailyReactivation() = runBlocking {
        val s = Setup(); val nextDate = date.plusDays(1)
        s.storage.exceptions = s.storage.exceptions + (nextDate to DateOverride(nextDate, DatePolicy.MANUAL, 2, LocalTime.of(6, 0)))
        s.gateway.historyByDate = { requested ->
            if (requested in s.gateway.submittedDates) listOf(completed().copy(id = "order-$requested", date = requested)) else emptyList()
        }
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date).status)
        assertTrue(s.storage.value.masterEnabled); assertEquals(LiveScope.RECURRING, s.storage.value.liveScope)
        s.clock.value = nextDate.atTime(6, 0).atZone(zone).toInstant()
        s.gateway.checkout = s.gateway.checkout.copy(date = nextDate, temporaryId = "temp-next")
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(nextDate).status)
        s.coordinator.execute(date); s.coordinator.execute(nextDate)
        assertEquals(listOf(date, nextDate), s.gateway.checkoutDates)
        assertEquals(listOf(date, nextDate), s.gateway.submittedDates)
        assertTrue(s.storage.value.masterEnabled); assertEquals(LiveScope.RECURRING, s.storage.value.liveScope)
        assertTrue(s.storage.value.displayPriceRiskAccepted)
        assertEquals(2, s.storage.ledger.count { it.status == ExecutionStatus.COMPLETED })
    }

    @Test fun priorDateUncertainIntentStaysQueryOnlyWhileNextDateRecurringPlanExecutes() = runBlocking {
        val s = Setup(); val nextDate = date.plusDays(1)
        s.storage.record(ExecutionRecord(date, 2, ExecutionStatus.NEEDS_CHECK, "SUBMIT_INTENT", "uncertain prior date",
            amount = 10000, submissionPossible = true, accountGeneration = 4, generation = 8))
        s.storage.exceptions = s.storage.exceptions + (nextDate to DateOverride(nextDate, DatePolicy.MANUAL, 2, LocalTime.of(6, 0)))
        s.clock.value = nextDate.atTime(6, 0).atZone(zone).toInstant()
        s.gateway.checkout = s.gateway.checkout.copy(date = nextDate, temporaryId = "temp-next")
        s.gateway.historyByDate = { requested ->
            if (requested in s.gateway.submittedDates) listOf(completed().copy(id = "order-$requested", date = requested)) else emptyList()
        }
        assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.execute(date).status)
        assertEquals(0, s.gateway.submits)
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(nextDate).status)
        assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.execute(date).status)
        assertEquals(listOf(nextDate), s.gateway.checkoutDates)
        assertEquals(listOf(nextDate), s.gateway.submittedDates)
        assertTrue(s.storage.ledger.single { it.date == date }.submissionPossible)
        assertTrue(s.storage.value.masterEnabled); assertNull(s.storage.value.liveBlockedReason)
    }

    @Test fun staleScheduledGenerationStopsBeforeAnyNetworkOrLedgerWrite() = runBlocking {
        val s = Setup()
        val result = s.coordinator.execute(date, expectedGeneration = 7, expectedAccountGeneration = 4)
        assertEquals(ExecutionStatus.SKIPPED, result.status); assertEquals("REQUEST_STALE", result.stage)
        assertTrue(s.gateway.logins.isEmpty()); assertEquals(0, s.gateway.identityChecks); assertEquals(0, s.gateway.reads)
        assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
        assertTrue(s.storage.ledger.isEmpty()); assertTrue(s.storage.writes.isEmpty())
    }

    @Test fun matchingCallerGenerationsAllowExactlyOneSubmission() = runBlocking {
        val s = Setup()
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date, expectedGeneration = 8, expectedAccountGeneration = 4).status)
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date, expectedGeneration = 8, expectedAccountGeneration = 4).status)
        assertEquals(1, s.gateway.creates); assertEquals(1, s.gateway.submits)
    }

    @Test fun staleManualApprovalAndAccountGenerationStopBeforeNetwork() = runBlocking {
        for ((generation, accountGeneration) in listOf(7L to 4L, 8L to 3L)) {
            val s = Setup(); s.storage.value = s.storage.value.copy(masterEnabled = false, liveScope = LiveScope.NONE)
            val result = s.coordinator.execute(date, manual = true, acceptedPriceRisk = true,
                expectedGeneration = generation, expectedAccountGeneration = accountGeneration)
            assertEquals("REQUEST_STALE", result.stage)
            assertTrue(s.gateway.logins.isEmpty()); assertEquals(0, s.gateway.reads)
            assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
            assertTrue(s.storage.ledger.isEmpty())
        }
    }

    @Test fun staleCallerWithExistingUncertainIntentRemainsQueryOnly() = runBlocking {
        val s = Setup()
        s.storage.record(ExecutionRecord(date, 2, ExecutionStatus.NEEDS_CHECK, "SUBMIT_INTENT", "uncertain",
            amount = 10000, submissionPossible = true, accountGeneration = 4, generation = 8))
        val result = s.coordinator.execute(date, expectedGeneration = 7, expectedAccountGeneration = 3)
        assertEquals(ExecutionStatus.NEEDS_CHECK, result.status)
        assertEquals(1, s.gateway.reads); assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
        assertTrue(s.storage.ledger.single().submissionPossible)
        assertEquals(10000L, s.storage.ledger.single().amount)
        assertEquals(4L, s.storage.ledger.single().accountGeneration)
    }

    @Test fun staleCallerWithExistingCompletedOrderRefreshesWithoutReposting() = runBlocking {
        val s = Setup(); s.gateway.history = { listOf(completed()) }
        s.storage.record(ExecutionRecord(date, 2, ExecutionStatus.COMPLETED, "VERIFIED", "existing",
            amount = 10000, accountGeneration = 4, generation = 8))
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date, expectedGeneration = 7).status)
        assertEquals(1, s.gateway.reads); assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
        assertEquals(ExecutionStatus.COMPLETED, s.storage.ledger.single().status)
    }

    @Test fun staleSkippedRequestDoesNotReplaceExistingSafeLedgerOrNotify() = runBlocking {
        val s = Setup()
        val previous = ExecutionRecord(date, 1, ExecutionStatus.FAILED, "PRE_TEMP", "newer ledger",
            accountGeneration = 4, generation = 8)
        s.storage.record(previous)
        val notices = mutableListOf<ExecutionRecord>()
        val coordinator = PurchaseCoordinator(s.storage, s.gateway, s.clock, wait = {}, onChanged = { notices.add(it) })
        assertEquals(ExecutionStatus.SKIPPED, coordinator.execute(date, expectedGeneration = 7).status)
        assertEquals(previous, s.storage.ledger.single()); assertEquals(1, s.storage.writes.size)
        assertTrue(notices.isEmpty()); assertTrue(s.gateway.logins.isEmpty())
    }

    @Test fun generationChangedWhileWaitingForMutexIsCheckedAtInitialPurchaseGate() = runBlocking {
        val s = Setup()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        s.gateway.beforeSession = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val first = async(Dispatchers.Default) { s.coordinator.refreshOrders(date.minusDays(1)) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) {
            secondStarted.countDown()
            s.coordinator.execute(date, expectedGeneration = 8, expectedAccountGeneration = 4)
        }
        assertTrue(secondStarted.await(3, TimeUnit.SECONDS))
        s.storage.value = s.storage.value.copy(generation = 9)
        s.gateway.beforeSession = {}
        release.countDown()
        first.await()
        assertEquals("REQUEST_STALE", second.await().stage)
        assertEquals(1, s.gateway.logins.size); assertEquals(1, s.gateway.reads)
        assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
        assertTrue(s.storage.ledger.none { it.date == date })
    }

    @Test fun cloudbricCredentialLoginBlockDoesNotRetryOrStartPurchase() = runBlocking {
        val s = Setup(); s.storage.value = s.storage.value.copy(retryCount = 10)
        s.gateway.expired = true
        s.gateway.credentialSessionFailure = SiteException("SECURITY_BLOCK_CONTRACT", "security block", false)
        assertEquals(ExecutionStatus.FAILED, s.coordinator.execute(date).status)
        assertEquals(2, s.gateway.logins.size); assertNull(s.gateway.logins.first())
        assertEquals("account", s.gateway.logins.last()?.userId)
        assertEquals(0, s.gateway.reads); assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
        assertTrue(s.waits.isEmpty()); assertTrue(s.storage.value.masterEnabled)
        assertTrue(s.storage.value.liveBlockedReason!!.startsWith("SECURITY_BLOCK_CONTRACT:"))
    }

    @Test fun cloudbricTemporaryPostBlockPreservesIntentAndNeverRetries() = runBlocking {
        val s = Setup(); s.storage.value = s.storage.value.copy(retryCount = 10)
        s.gateway.createFailure = SiteException("SECURITY_BLOCK_CONTRACT", "security block", true)
        assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.execute(date).status)
        s.coordinator.execute(date)
        assertEquals(1, s.gateway.creates); assertEquals(0, s.gateway.submits)
        assertTrue(s.storage.ledger.single().submissionPossible); assertTrue(s.waits.isEmpty())
        assertTrue(s.storage.value.liveBlockedReason!!.startsWith("SECURITY_BLOCK_CONTRACT:"))
    }

    @Test fun cloudbricHistoryBlockAfterFinalSubmissionPreservesAmbiguousLedger() = runBlocking {
        val s = Setup()
        s.gateway.history = { if (s.gateway.submits > 0) throw SiteException("SECURITY_BLOCK_CONTRACT", "security block") else emptyList() }
        assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.execute(date).status)
        s.coordinator.execute(date)
        assertEquals(1, s.gateway.creates); assertEquals(1, s.gateway.submits)
        assertTrue(s.storage.ledger.single().submissionPossible)
        assertNull(s.storage.value.verifiedLiveAccountGeneration)
        assertTrue(s.storage.value.liveBlockedReason!!.startsWith("SECURITY_BLOCK_CONTRACT:"))
    }

    @Test fun cloudbricFinalPostBlockDoesNotProveNonreceiptOrPermitRepost() = runBlocking {
        val s = Setup(); s.gateway.submitFailure = SiteException("SECURITY_BLOCK_CONTRACT", "security block", true)
        s.gateway.history = { emptyList() }
        assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.execute(date).status)
        s.coordinator.execute(date)
        assertEquals(1, s.gateway.creates); assertEquals(1, s.gateway.submits)
        assertTrue(s.storage.ledger.single().submissionPossible)
        assertEquals(10000L, s.storage.ledger.single().amount)
        assertTrue(s.storage.value.liveBlockedReason!!.startsWith("SECURITY_BLOCK_CONTRACT:"))
    }

    @Test fun futureFailedEmptyOrCanceledLookupDoesNotWriteOrPreventRescheduling() = runBlocking {
        val future = date.plusDays(6)
        listOf("error", "empty", "canceled").forEach { outcome ->
            val s = Setup()
            s.storage.exceptions = mapOf(future to DateOverride(future, DatePolicy.MANUAL, 2, LocalTime.of(6, 0)))
            s.gateway.history = {
                when (outcome) {
                    "error" -> throw SiteException("NETWORK", "offline")
                    "canceled" -> listOf(completed(status = "취소완료").copy(date = future))
                    else -> emptyList()
                }
            }
            val result = s.coordinator.refreshOrders(future)
            assertEquals(if (outcome == "error") "LOOKUP_ERROR" else "LOOKUP_EMPTY", result.stage)
            assertEquals(if (outcome == "error") ExecutionStatus.FAILED else ExecutionStatus.SKIPPED, result.status)
            assertFalse(result.submissionPossible); assertTrue(s.storage.writes.isEmpty())
            assertNull(s.storage.value.liveBlockedReason)
            s.storage.exceptions = mapOf(future to DateOverride(future, DatePolicy.EXCLUDE))
            s.storage.exceptions = mapOf(future to DateOverride(future, DatePolicy.MANUAL, 3, LocalTime.of(6, 0)))
            val plan = ScheduleCalculator.planFor(future, s.storage.value, s.storage.exceptions[future])!!
            assertEquals(plan, AlarmPlanSelector.next(s.storage.value, listOf(plan), s.storage.ledger, s.clock.instant()))
            assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
        }
    }

    @Test fun legacyObservationUsesLatestPlanQuantityAndSurvivesLookupWithoutRewrite() = runBlocking {
        val s = Setup()
        val legacy = ExecutionRecord(date, 5, ExecutionStatus.NEEDS_CHECK, "HISTORY", "lookup")
        s.storage.record(legacy)
        val writeCount = s.storage.writes.size
        s.storage.exceptions = mapOf(date to DateOverride(date, DatePolicy.MANUAL, 3, LocalTime.of(6, 0)))
        s.gateway.history = { emptyList() }
        assertEquals(3, s.coordinator.refreshOrders(date).quantity)
        s.storage.exceptions = mapOf(date to DateOverride(date, DatePolicy.EXCLUDE))
        assertEquals(s.storage.value.defaultQuantity, s.coordinator.refreshOrders(date).quantity)
        assertEquals(legacy, s.storage.ledger.single()); assertEquals(writeCount, s.storage.writes.size)
    }

    @Test fun lookupErrorOrEmptyPreservesActualIntentCompletionAndConflictWithoutRepost() = runBlocking {
        val records = listOf(
            ExecutionRecord(date, 2, ExecutionStatus.RUNNING, "TEMP_INTENT", "intent", submissionPossible = true, accountGeneration = 4),
            ExecutionRecord(date, 2, ExecutionStatus.NEEDS_CHECK, "SUBMIT_UNKNOWN", "intent", amount = 10000, submissionPossible = true, accountGeneration = 4),
            ExecutionRecord(date, 2, ExecutionStatus.COMPLETED, "VERIFIED", "done", amount = 10000, serverOrderId = "order", accountGeneration = 4),
            ExecutionRecord(date, 2, ExecutionStatus.NEEDS_CHECK, "HISTORY_CONFLICT", "conflict", accountGeneration = 4)
        )
        records.forEach { prior ->
            listOf(false, true).forEach { empty ->
                val s = Setup(); s.storage.record(prior)
                s.gateway.history = { if (empty) emptyList() else throw SiteException("NETWORK", "offline") }
                val result = s.coordinator.refreshOrders(date)
                assertEquals(ExecutionStatus.NEEDS_CHECK, result.status)
                assertEquals(if (empty) "LOOKUP_EMPTY" else "LOOKUP_ERROR", result.stage)
                assertEquals(prior.amount, result.amount); assertEquals(prior.serverOrderId, result.serverOrderId)
                assertEquals(prior, s.storage.ledger.single()); assertEquals(1, s.storage.writes.size)
                s.coordinator.execute(date)
                assertEquals(prior, s.storage.ledger.single()); assertEquals(1, s.storage.writes.size)
                assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
            }
        }
    }

    @Test fun legacyLookupNeverBypassesExecutionHistoryFailureOrConflict() = runBlocking {
        listOf(false, true).forEach { conflict ->
            val s = Setup()
            s.storage.record(ExecutionRecord(date, 2, ExecutionStatus.NEEDS_CHECK, "HISTORY_EMPTY", "lookup"))
            s.gateway.history = { if (conflict) listOf(completed(quantity = 1)) else throw SiteException("NETWORK", "offline") }
            val result = s.coordinator.execute(date)
            assertEquals(if (conflict) "HISTORY_CONFLICT" else "HISTORY", result.stage)
            assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
        }
    }

    @Test fun futureLookupFailureDoesNotPreventOneVerifiedPurchaseOnItsDate() = runBlocking {
        val s = Setup(); val future = date.plusDays(6)
        s.storage.exceptions = mapOf(future to DateOverride(future, DatePolicy.MANUAL, 2, LocalTime.of(6, 0)))
        s.gateway.history = { throw SiteException("NETWORK", "offline") }
        s.coordinator.refreshOrders(future)
        s.clock.value = future.atTime(6, 0).atZone(zone).toInstant()
        s.gateway.checkout = s.gateway.checkout.copy(date = future)
        s.gateway.history = { if (s.gateway.submits > 0) listOf(completed().copy(date = future)) else emptyList() }
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(future).status)
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(future).status)
        assertEquals(1, s.gateway.creates); assertEquals(1, s.gateway.submits)
    }

    @Test fun successfulLookupOfKnownServerOrderStillPersistsReconciledRecord() = runBlocking {
        val s = Setup(); s.gateway.history = { listOf(completed()) }
        val result = s.coordinator.refreshOrders(date)
        assertEquals(ExecutionStatus.COMPLETED, result.status)
        assertEquals("order", result.serverOrderId); assertEquals(10000L, result.amount)
        assertEquals(result, s.storage.ledger.single()); assertEquals(1, s.storage.writes.size)
        assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
    }

    @Test fun lookupSecurityBlockStillBlocksPurchasesWithoutInventingSubmissionLedger() = runBlocking {
        val s = Setup(); s.gateway.history = { throw SiteException("SECURITY_BLOCK_CONTRACT", "blocked") }
        assertEquals("LOOKUP_ERROR", s.coordinator.refreshOrders(date).stage)
        assertTrue(s.storage.ledger.isEmpty())
        assertTrue(s.storage.value.liveBlockedReason!!.startsWith("SECURITY_BLOCK_CONTRACT:"))
        assertEquals(ExecutionStatus.SKIPPED, s.coordinator.execute(date).status)
        assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
    }

    @Test fun accountChangeDuringPureLookupCannotPersistOldAccountResult() = runBlocking {
        val s = Setup(); s.gateway.history = {
            s.storage.value = s.storage.value.copy(accountGeneration = 5)
            listOf(completed())
        }
        assertEquals("LOOKUP_ERROR", s.coordinator.refreshOrders(date).stage)
        assertTrue(s.storage.ledger.isEmpty()); assertEquals(0, s.gateway.submits)
    }

    @Test fun coldServerTimeFailureNeverUsesPhoneClockOrWritesPurchaseLedger() = runBlocking {
        val s = ServerSetup()
        s.gateway.serverTime = { throw SiteException("TIME_UNAVAILABLE", "unavailable") }
        val result = s.coordinator.execute(date)
        assertEquals(ExecutionStatus.FAILED, result.status); assertEquals("PREPARATION", result.stage)
        assertEquals(0, s.gateway.menus); assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
        assertTrue(s.storage.writes.isEmpty())
    }

    @Test fun coldPhoneAheadOrBehindCannotPurchaseBeforeServerOpening() = runBlocking {
        listOf(-30_000L, 30_000L).forEach { phoneOffset ->
            val s = ServerSetup(serverOffset = -1_000, phoneOffset = phoneOffset)
            assertEquals("PREPARATION", s.coordinator.execute(date).stage)
            assertEquals(0, s.gateway.menus); assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
            assertTrue(s.storage.writes.isEmpty())
        }
    }

    @Test fun preparationWaitsForServerOpeningThenFreshMenuAndExactlyOnePurchase() = runBlocking {
        val s = ServerSetup(serverOffset = -120_000, phoneOffset = 30_000)
        var loadedMenu: MenuSnapshot? = null
        s.gateway.menuFactory = { plan, generation, accountGeneration -> menu(plan, generation, accountGeneration).also { loadedMenu = it } }
        s.gateway.beforeMenu = {
            assertNull(s.clock.purchaseProblem(date, LocalTime.of(6, 0)))
            assertEquals(120_000L, s.waits.sum())
        }
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.prepareAndExecute(date, 8).status)
        assertEquals(2, s.gateway.timeProbes)
        assertEquals(1, s.gateway.menus); assertEquals(1, s.gateway.creates); assertEquals(1, s.gateway.submits)
        assertSame(loadedMenu, s.gateway.receivedMenu)
        assertEquals(listOf("menu", "identity", "history", "temp"),
            s.gateway.events.drop(s.gateway.events.indexOf("menu")).take(4))
        s.coordinator.prepareAndExecute(date, 8)
        assertEquals(1, s.gateway.creates); assertEquals(1, s.gateway.submits)
        assertEquals(2, s.gateway.timeProbes)
    }

    @Test fun stopAccountOrPlanChangeDuringPreparationWaitCannotPostOrWriteLedger() = runBlocking {
        for (change in 0..2) {
            val s = ServerSetup(serverOffset = -10_000)
            s.afterWait = {
                when (change) {
                    0 -> s.storage.value = s.storage.value.copy(masterEnabled = false, generation = 9)
                    1 -> s.storage.value = s.storage.value.copy(accountGeneration = 5)
                    else -> s.storage.exceptions = mapOf(date to DateOverride(date, DatePolicy.MANUAL, 3, LocalTime.of(6, 0)))
                }
            }
            assertEquals("PREPARATION", s.coordinator.prepareAndExecute(date, 8).stage)
            assertEquals(0, s.gateway.menus); assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
            assertTrue(s.storage.writes.isEmpty())
        }
    }

    @Test fun preparationCancellationAndMaximumWaitCannotCreateSubmissionIntent() = runBlocking {
        val canceled = ServerSetup(serverOffset = -10_000)
        canceled.afterWait = { throw CancellationException("canceled") }
        try { canceled.coordinator.prepareAndExecute(date, 8); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertTrue(canceled.storage.writes.isEmpty()); assertEquals(0, canceled.gateway.creates)
        val timeout = ServerSetup(serverOffset = -240_000)
        assertEquals("PREPARATION", timeout.coordinator.prepareAndExecute(date, 8).stage)
        assertEquals(180_000L, timeout.waits.sum())
        assertTrue(timeout.storage.writes.isEmpty()); assertEquals(0, timeout.gateway.creates); assertEquals(0, timeout.gateway.submits)
    }

    @Test fun preparationWithUncertainOrCompletedLedgerIsQueryOnlyWithoutTimeProbe() = runBlocking {
        listOf(false, true).forEach { completed ->
            val s = ServerSetup()
            val prior = ExecutionRecord(date, 2, if (completed) ExecutionStatus.COMPLETED else ExecutionStatus.NEEDS_CHECK,
                if (completed) "VERIFIED" else "SUBMIT_UNKNOWN", "protected", amount = 10000,
                submissionPossible = !completed, accountGeneration = 4, generation = 8)
            s.storage.record(prior); s.gateway.history = { emptyList() }
            assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.prepareAndExecute(date, 7).status)
            assertEquals(prior, s.storage.ledger.single()); assertEquals(1, s.storage.writes.size)
            assertEquals(0, s.gateway.timeProbes); assertEquals(0, s.gateway.menus); assertEquals(0, s.gateway.creates)
        }
    }

    @Test fun serverClosingUncertaintyAndExpiredClockAtTempBoundaryPreventPost() = runBlocking {
        val closing = ServerSetup(serverOffset = 7_199_000, phoneOffset = 7_199_000)
        assertEquals("PREPARATION", closing.coordinator.execute(date).stage)
        assertEquals(0, closing.gateway.creates); assertEquals(0, closing.gateway.submits)
        val expired = ServerSetup()
        expired.gateway.beforeTempMutation = { expired.advance(180_001) }
        assertEquals(ExecutionStatus.FAILED, expired.coordinator.execute(date).status)
        assertEquals(0, expired.gateway.creates); assertEquals(0, expired.gateway.submits)
        assertFalse(expired.storage.ledger.single().submissionPossible)
    }

    @Test fun finalMutationRechecksClockAndMasterAfterBlockingPreparation() = runBlocking {
        listOf(false, true).forEach { stop ->
            val s = ServerSetup()
            s.gateway.beforeFinalMutation = {
                if (stop) s.storage.value = s.storage.value.copy(masterEnabled = false, generation = 9)
                else s.advance(180_001)
            }
            assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.execute(date).status)
            assertEquals(1, s.gateway.creates); assertEquals(0, s.gateway.submits)
            assertTrue(s.storage.ledger.single().submissionPossible)
        }
    }

    @Test fun cancellationDuringBlockingPrePostWorkIsCheckedBeforeMutation() = runBlocking {
        val s = ServerSetup()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        s.gateway.beforeTempMutation = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val operation = async(Dispatchers.Default) { s.coordinator.execute(date) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        operation.cancel()
        release.countDown()
        try { operation.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
        assertTrue(s.storage.ledger.single().submissionPossible)
    }

    @Test fun stalePreparationRequestDoesNotQueryOrOverwriteExistingSafeRecord() = runBlocking {
        val s = ServerSetup()
        val previous = ExecutionRecord(date, 2, ExecutionStatus.FAILED, "HISTORY", "previous", accountGeneration = 4, generation = 8)
        s.storage.record(previous)
        assertEquals("REQUEST_STALE", s.coordinator.prepareAndExecute(date, 7).stage)
        assertEquals(previous, s.storage.ledger.single()); assertEquals(1, s.storage.writes.size)
        assertTrue(s.gateway.logins.isEmpty()); assertEquals(0, s.gateway.timeProbes)
    }

    @Test fun freshMenuExpiryOfSessionUsesOptionalFallbackBeforeAnySubmissionIntent() = runBlocking {
        listOf(false, true).forEach { fallback ->
            val s = ServerSetup(); s.storage.value = s.storage.value.copy(useStoredCredentials = fallback)
            s.gateway.beforeMenu = {
                if (s.gateway.menus == 1) {
                    assertTrue(s.storage.ledger.isEmpty())
                    s.gateway.expired = true
                    throw SiteException("LOGIN_REQUIRED", "expired at target")
                }
            }
            s.gateway.menuFactory = { plan, generation, accountGeneration -> menu(plan, generation, accountGeneration) }
            val result = s.coordinator.execute(date)
            if (fallback) {
                assertEquals(ExecutionStatus.COMPLETED, result.status)
                assertEquals(2, s.gateway.menus); assertEquals(1, s.gateway.logins.count { it != null })
                assertEquals(1, s.gateway.creates); assertEquals(1, s.gateway.submits)
            } else {
                assertEquals(ExecutionStatus.FAILED, result.status)
                assertEquals(0, s.gateway.logins.count { it != null })
                assertFalse(s.storage.ledger.single().submissionPossible)
                assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
            }
        }
    }

    @Test fun staleMenuMetadataAndLargeInitialClockOffsetPreventAnyPurchasePost() = runBlocking {
        val stale = ServerSetup()
        stale.gateway.menuFactory = { plan, generation, accountGeneration -> menu(plan, generation - 1, accountGeneration) }
        assertEquals(ExecutionStatus.FAILED, stale.coordinator.execute(date).status)
        assertEquals(0, stale.gateway.creates); assertEquals(0, stale.gateway.submits)
        listOf(-300_001L, 300_001L).forEach { offset ->
            val s = ServerSetup(phoneOffset = offset)
            assertEquals("PREPARATION", s.coordinator.execute(date).stage)
            assertTrue(s.storage.writes.isEmpty()); assertEquals(0, s.gateway.menus); assertEquals(0, s.gateway.creates)
        }
    }

    @Test fun finalTimeRefreshDoesNotRepeatSlowSessionOrDelayTargetMenu() = runBlocking {
        val s = ServerSetup(serverOffset = -120_000)
        var sessionCalls = 0
        s.gateway.beforeSession = { sessionCalls++; s.advance(6_000) }
        s.gateway.menuFactory = { plan, generation, accountGeneration -> menu(plan, generation, accountGeneration) }
        s.gateway.beforeMenu = {
            assertEquals(date.atTime(6, 0).atZone(zone).toInstant().toEpochMilli(), s.serverEpoch)
        }
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.prepareAndExecute(date, 8).status)
        assertEquals(1, sessionCalls); assertEquals(2, s.gateway.timeProbes)
        assertEquals(114_000L, s.waits.sum())
        assertEquals(listOf("menu", "identity", "history", "temp"),
            s.gateway.events.drop(s.gateway.events.indexOf("menu")).take(4))
    }

    @Test fun twoSecondFinalProbeTimeoutKeepsValidSampleAndStillReachesTargetMenu() = runBlocking {
        val s = ServerSetup(serverOffset = -120_000)
        val target = date.atTime(6, 0).atZone(zone).toInstant().toEpochMilli()
        s.gateway.serverTime = {
            if (s.gateway.timeProbes == 1) ServerTimeSample(s.serverEpoch, s.elapsed, s.elapsed, s.wall.millis())
            else {
                assertEquals(target - 15_000, s.serverEpoch)
                s.advance(2_000)
                throw SiteException("NETWORK", "probe timeout")
            }
        }
        s.gateway.menuFactory = { plan, generation, accountGeneration -> menu(plan, generation, accountGeneration) }
        s.gateway.beforeMenu = { assertEquals(target, s.serverEpoch) }
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.prepareAndExecute(date, 8).status)
        assertEquals(2, s.gateway.timeProbes); assertEquals(118_000L, s.waits.sum())
        assertEquals(1, s.gateway.creates); assertEquals(1, s.gateway.submits)
    }

    @Test fun finalProbeTimeoutCannotReuseSampleThatExpiresDuringTheCall() = runBlocking {
        val s = ServerSetup(serverOffset = -174_000)
        s.gateway.serverTime = {
            if (s.gateway.timeProbes == 1) ServerTimeSample(s.serverEpoch - 20_000, s.elapsed - 20_000,
                s.elapsed - 20_000, s.wall.millis() - 20_000)
            else {
                s.advance(2_000)
                throw SiteException("NETWORK", "probe timeout")
            }
        }
        assertEquals("PREPARATION", s.coordinator.prepareAndExecute(date, 8).stage)
        assertEquals(2, s.gateway.timeProbes); assertEquals(159_000L, s.waits.sum())
        assertTrue(s.storage.writes.isEmpty()); assertNull(s.clock.bounds())
        assertEquals(0, s.gateway.menus); assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
    }

    @Test fun conflictingFinalSampleAndNonNetworkProbeFailuresNeverFallBackToOldTime() = runBlocking {
        listOf("CONFLICT", "TIME_UNAVAILABLE", "SECURITY_BLOCK_CONTRACT").forEach { outcome ->
            val s = ServerSetup(serverOffset = -20_000)
            s.gateway.serverTime = {
                if (s.gateway.timeProbes == 1) ServerTimeSample(s.serverEpoch, s.elapsed, s.elapsed, s.wall.millis())
                else if (outcome == "CONFLICT") ServerTimeSample(s.serverEpoch + 20_000, s.elapsed, s.elapsed, s.wall.millis())
                else throw SiteException(outcome, "invalid probe")
            }
            assertEquals("PREPARATION", s.coordinator.prepareAndExecute(date, 8).stage)
            assertEquals(2, s.gateway.timeProbes); assertTrue(s.storage.writes.isEmpty())
            assertEquals(0, s.gateway.menus); assertEquals(0, s.gateway.creates); assertEquals(0, s.gateway.submits)
            if (outcome == "CONFLICT") assertNull(s.clock.bounds())
            if (outcome == "SECURITY_BLOCK_CONTRACT") assertNotNull(s.storage.value.liveBlockedReason)
        }
    }
}
