package com.fullmetalsonic.dosirak

import org.junit.Assert.assertEquals
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
}
