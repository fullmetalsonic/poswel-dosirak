package com.fullmetalsonic.dosirak.data

import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class OnboardingStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun newInstallationKeepsGuideUntilExplicitCompletion() {
        val name = "onboarding-${UUID.randomUUID()}"
        try {
            OnboardingStore(context, name).apply {
                initialize(false)
                assertTrue(isRequired())
                initialize(true)
                assertTrue(isRequired())
                complete()
            }
            assertFalse(OnboardingStore(context, name).isRequired())
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun existingInstallationSkipsGuideWithoutChangingReservationData() {
        val name = "onboarding-${UUID.randomUUID()}"
        val database = "$name.db"
        val date = LocalDate.of(2026, 10, 15)
        val settings = AppSettings(masterEnabled = true, patternConfirmed = true,
            liveScope = LiveScope.RECURRING, displayPriceRiskAccepted = true, generation = 17,
            liveBlockedReason = "TEST_BLOCK")
        val exception = DateOverride(date, DatePolicy.EXCLUDE, reason = ReservationReason.VACATION)
        val record = ExecutionRecord(date, 1, ExecutionStatus.NEEDS_CHECK, "SUBMIT_INTENT", "test", submissionPossible = true)
        try {
            AppStore(context, database).use { store ->
                assertFalse(store.hasSavedData())
                store.saveSettings(settings)
                store.saveOverride(exception)
                store.record(record)
                OnboardingStore(context, name).apply {
                    initialize(store.hasSavedData())
                    assertFalse(isRequired())
                }
                assertEquals(settings, store.loadSettings())
                assertEquals(exception, store.loadOverrides()[date])
                assertEquals(record, store.loadRecords().single())
            }
        } finally {
            context.deleteDatabase(database)
            context.deleteSharedPreferences(name)
        }
    }

    @Test fun legacyStoredAccountAloneSkipsInitialGuide() {
        val name = "onboarding-${UUID.randomUUID()}"
        try {
            OnboardingStore(context, name).initialize(true)
            assertFalse(OnboardingStore(context, name).isRequired())
        } finally { context.deleteSharedPreferences(name) }
    }
}
