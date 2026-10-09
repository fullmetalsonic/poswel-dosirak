package com.fullmetalsonic.dosirak.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsLimitStoreTest {
    @Test fun disabledLimitsCommitWithoutAmountsAndPreservePurchaseApproval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "limits-${System.nanoTime()}.db"
        try {
            AppStore(context, name).use { store ->
                val old = AppSettings(masterEnabled = true, liveScope = LiveScope.RECURRING,
                    displayPriceRiskAccepted = true, unitLimit = 5000, orderLimit = 10000, generation = 4)
                store.saveSettings(old)
                val saved = ActivationRules.settingsForSave(old, old.copy(limitEnabled = false,
                    unitLimit = null, orderLimit = null), old.generation)
                store.saveSettings(saved)
                val read = store.loadSettings()
                assertFalse(read.limitEnabled)
                assertNull(read.unitLimit)
                assertNull(read.orderLimit)
                assertTrue(read.masterEnabled)
                assertTrue(read.displayPriceRiskAccepted)
                assertEquals(LiveScope.RECURRING, read.liveScope)
                assertEquals(5, read.generation)
                assertNull(ReservationLimits.validate(read))
            }
        } finally { context.deleteDatabase(name) }
    }
}
