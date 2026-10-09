package com.fullmetalsonic.dosirak.web

import org.junit.Assert.*
import org.junit.Test

class WebNavigationTest {
    @Test fun samePathWithNewNonceLoadsOnceAfterBusyEnds() {
        assertFalse(WebNavigation.shouldGet(2, 1, busy = true, viewReady = true, initialLoadPending = false))
        assertTrue(WebNavigation.shouldGet(2, 1, busy = false, viewReady = true, initialLoadPending = false))
        assertFalse(WebNavigation.shouldGet(2, 2, busy = false, viewReady = true, initialLoadPending = false))
    }

    @Test fun factoryAndEffectShareOnlyOneInitialGet() {
        assertFalse(WebNavigation.shouldGet(1, 0, busy = false, viewReady = false, initialLoadPending = true))
        assertTrue(WebNavigation.shouldGet(1, 0, busy = false, viewReady = true, initialLoadPending = true))
        assertFalse(WebNavigation.shouldGet(1, 1, busy = false, viewReady = true, initialLoadPending = false))
    }

    @Test fun restoreDoesNotRepeatHandledRequestOrInitialControllerZero() {
        assertEquals(4, WebNavigation.handledAfterRestore(4, 4))
        assertFalse(WebNavigation.shouldGet(4, 4, false, true, false))
        val restored = WebNavigation.handledAfterRestore(4, 0)
        assertEquals(0, restored)
        assertFalse(WebNavigation.shouldGet(0, restored, false, true, false))
        assertTrue(WebNavigation.shouldGet(1, restored, false, true, false))
    }

    @Test fun pendingRequestSurvivesSafeRestoreWhileBusy() {
        val restored = WebNavigation.handledAfterRestore(2, 3)
        assertEquals(2, restored)
        assertFalse(WebNavigation.shouldGet(3, restored, true, true, false))
        assertTrue(WebNavigation.shouldGet(3, restored, false, true, false))
    }

    @Test fun protectedPostAndCheckoutRoutesCannotBecomeProgrammaticRecoveryGet() {
        listOf("/login.check.php", "/order.info.php", "/togobox/order.temp.php",
            "/togobox/get.order.temp.php", "/togobox/order.reg.php").forEach { path ->
            assertEquals(WebNavigation.HOME, WebNavigation.target(path))
            assertFalse(WebNavigation.canRestoreHistory(listOf(WebNavigation.HOME, WebNavigation.HOME.dropLast(1) + path), WebMethod.GET))
        }
        assertFalse(WebNavigation.canRestoreHistory(listOf(WebNavigation.HOME), WebMethod.POST))
        assertFalse(WebNavigation.canRestoreHistory(listOf(WebNavigation.HOME), WebMethod.UNKNOWN))
        assertTrue(WebNavigation.canRestoreHistory(listOf(WebNavigation.HOME), WebMethod.GET))
    }

    @Test fun invalidOrExternalTargetsFallBackHomeAndHistoryRemainsAllowed() {
        assertEquals(WebNavigation.HOME, WebNavigation.target("https://example.com/"))
        assertEquals(WebNavigation.HOME, WebNavigation.target("https://user:secret@dosirak.poswel.co.kr/"))
        assertEquals(WebNavigation.HOME, WebNavigation.target("http://dosirak.poswel.co.kr/"))
        assertEquals(WebNavigation.HOME, WebNavigation.target("https://dosirak.poswel.co.kr:444/"))
        assertEquals("https://dosirak.poswel.co.kr/order.list.php", WebNavigation.target("/order.list.php"))
    }
}
