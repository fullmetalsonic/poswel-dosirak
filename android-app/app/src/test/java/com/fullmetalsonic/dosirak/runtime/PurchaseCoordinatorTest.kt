package com.fullmetalsonic.dosirak.runtime

import com.fullmetalsonic.dosirak.domain.*
import com.fullmetalsonic.dosirak.site.*
import kotlinx.coroutines.Dispatchers
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
        var beforeCheckout: () -> Unit = {}
        var afterSubmit: () -> Unit = {}
        var createFailure: SiteException? = null
        var submitFailure: SiteException? = null
        var checkout = CheckoutSnapshot(date, 2, 5000, 10000, "account", "temp", emptyMap())
        var history: () -> List<SiteOrder> = { if (submits > 0) listOf(completed()) else emptyList() }
        var historyByDate: ((LocalDate) -> List<SiteOrder>)? = null
        override fun ensureSession(credentials: Credentials?) {
            logins.add(credentials)
            if (expired && credentials == null) throw SiteException("LOGIN_REQUIRED", "expired")
        }
        override fun verifyAccount(expectedAccountId: String) {
            identityChecks++
            identityFailure?.let { throw it }
            if (sessionAccount != expectedAccountId) throw SiteException("ACCOUNT_MISMATCH", "different account")
        }
        override fun readOrders(date: LocalDate): List<SiteOrder> { reads++; return historyByDate?.invoke(date) ?: history() }
        override fun createCheckout(plan: OrderPlan): CheckoutSnapshot {
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
            submits++
            submittedDates.add(checkout.date)
            val intent = storage.ledger.single { it.date == checkout.date }
            assertTrue(intent.submissionPossible)
            assertEquals("SUBMIT_INTENT", intent.stage)
            afterSubmit()
            submitFailure?.let { throw it }
            return SubmitReceipt("ok", 200)
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

    private fun completed(quantity: Int? = 2, total: Long? = 10000, status: String = "주문완료") =
        SiteOrder("order", date, quantity, status, total, "menu")

    @Test fun recurringConsentAllowsFirstPurchaseAndSubmissionIsExactlyOnce() = runBlocking {
        val s = Setup()
        assertNull(s.storage.value.verifiedLiveAccountGeneration)
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date).status)
        assertEquals(4L, s.storage.value.verifiedLiveAccountGeneration)
        assertEquals(ExecutionStatus.COMPLETED, s.coordinator.execute(date).status)
        assertEquals(1, s.gateway.creates); assertEquals(1, s.gateway.submits)
        assertNull(s.gateway.logins.first())
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
        assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.refreshOrders(date).status)
        assertEquals(0, s.gateway.reads); assertEquals(1, s.gateway.identityChecks)
        assertNotNull(s.storage.value.liveBlockedReason)
        assertEquals(listOf<Credentials?>(null), s.gateway.logins)
    }

    @Test fun refreshRequiresSavedAccountIdentityBeforeQuery() = runBlocking {
        val s = Setup(); s.storage.account = null; s.gateway.history = { listOf(completed()) }
        assertEquals(ExecutionStatus.NEEDS_CHECK, s.coordinator.refreshOrders(date).status)
        assertEquals(0, s.gateway.reads); assertEquals(0, s.gateway.identityChecks)
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
}
