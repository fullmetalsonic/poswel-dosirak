package com.fullmetalsonic.dosirak.web

import com.fullmetalsonic.dosirak.diagnostics.*
import org.junit.Assert.*
import org.junit.Test

class WebCaptureLifecycleTest {
    private val checkout = "https://dosirak.poswel.co.kr/order.info.php"
    private val submit = "https://dosirak.poswel.co.kr/togobox/order.reg.php"
    private val history = "https://dosirak.poswel.co.kr/order.list.php"
    @Test fun pendingAttemptExpiresAndUnrelatedNavigationDoesNotMislabelLaterHistory() {
        var now = 10L
        val lifecycle = WebCaptureLifecycle { now }
        lifecycle.started(checkout); lifecycle.orderIntent()
        now += 120_001
        lifecycle.started(history)
        assertEquals(DetailWebPhase.HISTORY_BEFORE, lifecycle.historyPhase())
        lifecycle.cancelObserved()
        lifecycle.started("https://dosirak.poswel.co.kr/")
        lifecycle.started(history)
        assertEquals(DetailWebPhase.HISTORY_VIEW, lifecycle.historyPhase())
        assertNull(lifecycle.pendingHistory())
    }
    @Test fun nativeAlertRemainsObservableBeforeNewDocumentHandshakeButRejectsStaleAndExternalCallbacks() {
        val lifecycle = WebCaptureLifecycle()
        lifecycle.started(checkout); lifecycle.started(submit)
        assertEquals(2L, lifecycle.generation)
        assertNull(lifecycle.alert(checkout, submit, "주문이 완료 되었습니다."))
        assertNull(lifecycle.alert("https://evil.test/", "https://evil.test/", "주문이 완료 되었습니다."))
        assertEquals(DetailSiteAlert.ORDER_COMPLETED, lifecycle.alert(submit, submit, "주문이 완료 되었습니다."))
        lifecycle.started(history)
        assertEquals(DetailWebPhase.HISTORY_AFTER_ORDER, lifecycle.historyPhase())
        assertEquals(DetailSiteAlert.CANCEL_COMPLETED, lifecycle.alert(history, history, "취소처리 되었습니다."))
        lifecycle.started(history)
        assertEquals(DetailWebPhase.HISTORY_AFTER_CANCEL, lifecycle.historyPhase())
    }
    @Test fun unknownPrivateAlertIsOnlyAnEnumAndDoesNotInventCompletion() {
        val lifecycle = WebCaptureLifecycle(); lifecycle.started(history)
        assertEquals(DetailSiteAlert.UNKNOWN_OMITTED, lifecycle.alert(history, history, "PRIVATE_ADDRESS_PHONE_TOKEN"))
        assertEquals(DetailSiteAlert.UNKNOWN_OMITTED, lifecycle.alert(history, history, "주문이 완료 되었습니다."))
        assertEquals(DetailWebPhase.HISTORY_BEFORE, lifecycle.historyPhase())
        assertNull(lifecycle.pendingHistory())
    }
    @Test fun knownFailuresAreBoundedEnumsAndPendingResultDoesNotProveSuccess() {
        val lifecycle = WebCaptureLifecycle(); lifecycle.started(checkout)
        listOf("주문이 마감되었습니다." to DetailSiteAlert.ORDER_CLOSED,
            "로그인이 필요합니다." to DetailSiteAlert.LOGIN_REQUIRED,
            "1인당 1개만 주문 가능합니다." to DetailSiteAlert.QUANTITY_LIMIT,
            "장바구니가 비어있습니다." to DetailSiteAlert.EMPTY_CART).forEach { (text, expected) ->
            assertEquals(expected, lifecycle.alert(checkout, checkout, text))
        }
        lifecycle.orderIntent()
        assertEquals(DetailWebPhase.HISTORY_AFTER_ORDER, lifecycle.pendingHistory())
        lifecycle.resetObservations()
        assertNull(lifecycle.pendingHistory())
        assertFalse(lifecycle.historySeen)
        assertEquals(DetailWebPhase.HISTORY_BEFORE, lifecycle.historyPhase())
    }
}
