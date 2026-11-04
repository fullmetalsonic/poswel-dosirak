package com.fullmetalsonic.dosirak.data

import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.domain.*
import com.fullmetalsonic.dosirak.runtime.PurchaseCoordinator
import com.fullmetalsonic.dosirak.runtime.PurchaseStorage
import com.fullmetalsonic.dosirak.site.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

class PurchaseBlockMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val date = LocalDate.of(2026, 11, 5)
    private val zone = ZoneId.of("Asia/Seoul")

    private fun settings(enabled: Boolean = true) = AppSettings(masterEnabled = enabled,
        liveScope = LiveScope.RECURRING, displayPriceRiskAccepted = true, patternConfirmed = true,
        accountGeneration = 4, verifiedLiveAccountGeneration = 4, generation = 8,
        limitEnabled = false, liveBlockedReason = "HISTORY_CONTRACT: legacy block")

    private fun rawSettings(store: AppStore): AppSettings = store.readableDatabase
        .rawQuery("SELECT json FROM config WHERE id=1", null).use {
            check(it.moveToFirst())
            AppJson.gson.fromJson(it.getString(0), AppSettings::class.java)
        }

    @Test fun coldReloadRecoversOnlyObsoleteGateAndPersistsOnceWithoutChangingPrivateState() {
        for (enabled in listOf(false, true)) {
            val name = "block-migration-${UUID.randomUUID()}.db"
            val original = settings(enabled)
            val expected = original.copy(liveBlockedReason = null, generation = 9)
            val override = DateOverride(date, DatePolicy.MANUAL, 1, LocalTime.of(6, 0))
            val first = ExecutionRecord(date, 1, ExecutionStatus.NEEDS_CHECK, "SUBMIT_UNKNOWN", "uncertain",
                Instant.parse("2026-11-04T21:00:00Z"), 5000, submissionPossible = true, accountGeneration = 4, generation = 8)
            val current = first.copy(message = "still uncertain")
            val baseline = first.copy(status = ExecutionStatus.COMPLETED, stage = "VERIFIED", serverOrderId = "fixture-order")
            val rebooking = RebookingState(date, "account", 4, baseline, "menu", listOf("fixture-cancel"), first.updatedAt,
                proof = listOf("fixture-cancel"), proofAt = first.updatedAt,
                permit = RebookingPermit("fixture-permit", 1, LocalTime.of(6, 10), 8, consumed = true))
            try {
                AppStore(context, name).use { store ->
                    store.saveOverride(override)
                    store.record(first); store.record(current)
                    store.saveRebookingState(rebooking)
                    store.saveSettings(original)
                    assertEquals(original, rawSettings(store))
                }
                repeat(2) {
                    AppStore(context, name).use { store ->
                        assertEquals(expected, store.loadSettings())
                        assertEquals(expected, store.loadSettings())
                        assertEquals(expected, rawSettings(store))
                        assertEquals(mapOf(date to override), store.loadOverrides())
                        assertEquals(listOf(current), store.loadRecords())
                        assertEquals(listOf(first), store.loadArchivedRecords())
                        assertEquals(rebooking, store.rebookingState(date))
                    }
                }
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test fun criticalUnknownAndFreeTextBlocksSurviveStoreReopenUnchanged() {
        val name = "block-migration-${UUID.randomUUID()}.db"
        try {
            for (reason in listOf("ACCOUNT_MISMATCH: test", "ACCOUNT_UNVERIFIED: test", "ADDITIONAL_AUTH: test",
                "PRICE_MISSING: test", "PRICE_INVALID: test", "PRICE_MISMATCH: test", "SECURITY_BLOCK_CONTRACT: test",
                "REDIRECT_BLOCKED: test", "UNKNOWN_CONTRACT: test", "신청정보의 계정·날짜·수량·금액 연결 확인 필요")) {
                val original = settings().copy(liveBlockedReason = reason)
                AppStore(context, name).use { it.saveSettings(original) }
                AppStore(context, name).use {
                    assertEquals(original, it.loadSettings())
                    assertEquals(original, rawSettings(it))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun recoveredHistoryBlockKeepsAmbiguousDateQueryOnlyDeduplicatesManualOrderAndRunsNextDate() = runBlocking {
        val name = "block-migration-${UUID.randomUUID()}.db"
        val ambiguousDate = date.minusDays(1)
        val nextDate = date.plusDays(1)
        val ambiguous = ExecutionRecord(ambiguousDate, 1, ExecutionStatus.NEEDS_CHECK, "SUBMIT_UNKNOWN", "uncertain",
            Instant.parse("2026-11-03T21:00:00Z"), 5000, submissionPossible = true, accountGeneration = 4, generation = 8)
        try {
            AppStore(context, name).use { store ->
                listOf(date, nextDate).forEach { store.saveOverride(DateOverride(it, DatePolicy.MANUAL, 1, LocalTime.of(6, 0))) }
                store.record(ambiguous)
                store.saveSettings(settings())
            }
            AppStore(context, name).use { store ->
                assertEquals(9L, store.loadSettings().generation)
                val storage = object : PurchaseStorage {
                    override fun settings() = store.loadSettings()
                    override fun overrides() = store.loadOverrides()
                    override fun records() = store.loadRecords()
                    override fun saveSettings(settings: AppSettings) = store.saveSettings(settings)
                    override fun record(record: ExecutionRecord) = store.record(record)
                    override fun credentials() = Credentials("account", "fixture")
                }
                var now = date.atTime(6, 0).atZone(zone).toInstant()
                val clock = object : Clock() {
                    override fun instant() = now
                    override fun getZone() = this@PurchaseBlockMigrationTest.zone
                    override fun withZone(zone: ZoneId) = Clock.fixed(now, zone)
                }
                var creates = 0
                val submittedDates = mutableListOf<LocalDate>()
                val gateway = object : SiteGateway {
                    override fun ensureSession(credentials: Credentials?) = Unit
                    override fun verifyAccount(expectedId: String) { assertEquals("account", expectedId) }
                    override fun readOrders(date: LocalDate): List<SiteOrder> =
                        if (date == this@PurchaseBlockMigrationTest.date || date in submittedDates)
                            listOf(SiteOrder("fixture-$date", date, 1, "주문완료", 5000, "menu")) else emptyList()
                    override fun createCheckout(plan: OrderPlan): CheckoutSnapshot {
                        creates++
                        return CheckoutSnapshot(plan.date, plan.quantity, 5000, 5000, "account", "fixture-temp", emptyMap())
                    }
                    override fun submit(checkout: CheckoutSnapshot): SubmitReceipt {
                        submittedDates.add(checkout.date)
                        return SubmitReceipt("fixture", 200)
                    }
                }
                val coordinator = PurchaseCoordinator(storage, gateway, clock, wait = {})
                assertEquals(ExecutionStatus.NEEDS_CHECK, coordinator.execute(ambiguousDate).status)
                assertEquals(ExecutionStatus.COMPLETED, coordinator.execute(date).status)
                assertEquals(0, creates); assertTrue(submittedDates.isEmpty())
                now = nextDate.atTime(6, 0).atZone(zone).toInstant()
                assertEquals(ExecutionStatus.COMPLETED, coordinator.execute(nextDate).status)
                assertEquals(ExecutionStatus.NEEDS_CHECK, coordinator.execute(ambiguousDate).status)
                assertEquals(1, creates); assertEquals(listOf(nextDate), submittedDates)
                assertTrue(store.loadRecords().single { it.date == ambiguousDate }.submissionPossible)
                assertEquals(5000L, store.loadRecords().single { it.date == ambiguousDate }.amount)
                assertEquals(settings().copy(liveBlockedReason = null, generation = 9), store.loadSettings())
            }
        } finally { context.deleteDatabase(name) }
    }
}
