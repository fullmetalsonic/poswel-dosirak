package com.fullmetalsonic.dosirak.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BatterySettingsRouteTest {
    @Test fun nonExemptExplicitClickRequestsExemptionBeforeAppDetailsFallback() {
        assertEquals(listOf(
            "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.settings.APPLICATION_DETAILS_SETTINGS"
        ), batterySettingsActions(false))
    }

    @Test fun exemptStaleExplicitClickOnlyOpensAppDetails() {
        assertEquals(listOf(
            "android.settings.APPLICATION_DETAILS_SETTINGS"
        ), batterySettingsActions(true))
    }

    @Test fun batteryRouteNeverOpensGlobalOptimizationList() {
        listOf(false, true).forEach { batteryExempt ->
            assertFalse(batterySettingsActions(batteryExempt).contains(
                "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"
            ))
        }
    }
}
