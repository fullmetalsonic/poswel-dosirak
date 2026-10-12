package com.fullmetalsonic.dosirak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionRouteTest {
    @Test fun firstExplicitClickRequestsPermission() {
        assertEquals(NotificationPermissionRoute.REQUEST, notificationPermissionRoute(true, false, false, false))
    }
    @Test fun rationaleAllowsAnExplicitRetry() {
        assertEquals(NotificationPermissionRoute.REQUEST, notificationPermissionRoute(true, false, true, true))
    }
    @Test fun previouslyAskedWithoutRationaleGoesToSettingsOnNextClick() {
        assertEquals(NotificationPermissionRoute.SETTINGS, notificationPermissionRoute(true, false, true, false))
    }
    @Test fun grantedOrPreRuntimePermissionUsesSystemSettings() {
        assertEquals(NotificationPermissionRoute.SETTINGS, notificationPermissionRoute(true, true, true, false))
        assertEquals(NotificationPermissionRoute.SETTINGS, notificationPermissionRoute(false, false, false, false))
    }
    @Test fun initialLoadNeverCountsAsCompletingSetupOrActivatingAutomaticOrders() {
        assertFalse(notificationPermissionNeededAfterSetup(null, null, true, false))
        assertFalse(notificationPermissionNeededAfterSetup(null, null, false, true))
        assertFalse(notificationPermissionNeededAfterSetup(null, null, false, false))
    }
    @Test fun successfulAutomaticActivationAndOnboardingCompletionRequestAccess() {
        assertTrue(notificationPermissionNeededAfterSetup(false, false, true, false))
        assertTrue(notificationPermissionNeededAfterSetup(false, true, false, false))
    }
    @Test fun failedSaveUnchangedResumeAndStoppingAutomaticOrdersDoNotRequestAccess() {
        assertFalse(notificationPermissionNeededAfterSetup(false, false, false, false))
        assertFalse(notificationPermissionNeededAfterSetup(true, false, true, false))
        assertFalse(notificationPermissionNeededAfterSetup(true, false, false, false))
        assertFalse(notificationPermissionNeededAfterSetup(false, true, false, true))
    }
}
