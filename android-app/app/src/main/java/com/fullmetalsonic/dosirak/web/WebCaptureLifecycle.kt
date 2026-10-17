package com.fullmetalsonic.dosirak.web

import com.fullmetalsonic.dosirak.diagnostics.DetailSiteAlert
import com.fullmetalsonic.dosirak.diagnostics.DetailWebPhase
import java.net.URI

/** Native callbacks survive a document change; none of these facts authorize a transaction. */
internal class WebCaptureLifecycle(private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    var generation = 0L; private set
    private var page: String? = null
    private var pending: DetailWebPhase? = null
    private var pendingAt: Long? = null
    var historySeen = false; private set

    fun started(url: String?) {
        generation++; page = url
        if (path(url) !in setOf("/order.info.php", "/togobox/order.reg.php", "/order.list.php")) clearPending()
    }
    fun resetObservations() { clearPending(); historySeen = false }
    fun accepts(url: String?, current: String?): Boolean = url != null && url == page && url == current &&
        WebCaptureDecoder.trustedPage(url) && path(url) in ROUTES
    fun alert(url: String?, current: String?, message: String?): DetailSiteAlert? {
        if (!accepts(url, current)) return null
        val result = when (message?.takeIf { it.length <= 256 }?.trim()) {
            "주문이 완료 되었습니다." -> DetailSiteAlert.ORDER_COMPLETED
            "취소처리 되었습니다." -> DetailSiteAlert.CANCEL_COMPLETED
            "주문이 마감되었습니다." -> DetailSiteAlert.ORDER_CLOSED
            "로그인이 필요합니다.", "로그인 후 이용해 주세요." -> DetailSiteAlert.LOGIN_REQUIRED
            "1인당 1개만 주문 가능합니다.", "1인당 1개만 신청 가능합니다." -> DetailSiteAlert.QUANTITY_LIMIT
            "장바구니가 비어있습니다.", "장바구니가 비어 있습니다." -> DetailSiteAlert.EMPTY_CART
            else -> DetailSiteAlert.UNKNOWN_OMITTED
        }
        val path = path(url)
        if (result == DetailSiteAlert.ORDER_COMPLETED && path !in setOf("/order.info.php", "/togobox/order.reg.php")) return DetailSiteAlert.UNKNOWN_OMITTED
        if (result == DetailSiteAlert.CANCEL_COMPLETED && path != "/order.list.php") return DetailSiteAlert.UNKNOWN_OMITTED
        when (result) {
            DetailSiteAlert.ORDER_COMPLETED -> markPending(DetailWebPhase.HISTORY_AFTER_ORDER)
            DetailSiteAlert.CANCEL_COMPLETED -> markPending(DetailWebPhase.HISTORY_AFTER_CANCEL)
            else -> Unit
        }
        return result
    }
    fun orderIntent() { markPending(DetailWebPhase.HISTORY_AFTER_ORDER) }
    fun cancelObserved() { markPending(DetailWebPhase.HISTORY_AFTER_CANCEL) }
    fun pendingHistory(): DetailWebPhase? {
        val age = pendingAt?.let { elapsedMillis() - it }
        if (age == null || age !in 0..120_000) clearPending()
        return pending
    }
    private fun markPending(phase: DetailWebPhase) { pending = phase; pendingAt = elapsedMillis() }
    private fun clearPending() { pending = null; pendingAt = null }
    fun historyPhase(): DetailWebPhase {
        val phase = pendingHistory() ?: if (historySeen) DetailWebPhase.HISTORY_VIEW else DetailWebPhase.HISTORY_BEFORE
        historySeen = true
        clearPending()
        return phase
    }
    companion object {
        private val ROUTES = setOf("/order.info.php", "/togobox/order.reg.php", "/order.list.php", "/", "/index.php")
        fun path(url: String?) = runCatching { URI(url).path }.getOrNull()
    }
}
