package com.fullmetalsonic.dosirak.web

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.JsResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.fullmetalsonic.dosirak.diagnostics.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Local intercepted fixtures only. Never opens the real network or app diagnostic storage. */
@RunWith(AndroidJUnit4::class)
class WebCaptureBridgeTest {
    @Test fun alertRedirectAndCancelReloadKeepTypedHistoryWithoutReplayingRequests() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val diagnostics = FixtureDiagnostics()
        val submitted = AtomicBoolean(false); val cancelled = AtomicBoolean(false)
        val posts = AtomicInteger(); val alerts = AtomicInteger()
        lateinit var view: WebView; lateinit var capture: WebCapture
        fun historyHtml(): String {
            val ordered = submitted.get() && !cancelled.get()
            return """<html><body><table id="dataTable"><tbody><tr>
                <td class="date">2026-10-18 (일)</td><td class="menu">PRIVATE_MENU</td>
                <td class="qtt">${if (ordered) "1" else "0"}</td><td class="state">${if (ordered) "주문완료" else "취소"}</td>
                <td class="pay">${if (ordered) "5,000 원" else ""}</td><td>PRIVATE_ADDRESS_PHONE</td>
                <td${if (ordered) " class=\"btn-cancle\"" else ""}>${if (ordered) "<a href=\"javascript:jsOrderCxl('123');\">주문취소</a>" else "26.10.18 07:05"}</td></tr></tbody></table>
                <script>function cancelFixture(){var x=new XMLHttpRequest();x.open('POST','/togobox/ajax/order.cancel.php',false);x.send('oNo=PRIVATE_ID');alert('취소처리 되었습니다.');location.reload();}</script></body></html>"""
        }
        instrumentation.runOnMainSync {
            view = WebView(instrumentation.targetContext); view.settings.javaScriptEnabled = true; view.settings.blockNetworkLoads = true
            capture = WebCapture(view, { fail("Supported fixture unavailable") }, diagnostics)
            view.webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    capture.jsAlert(url, message)
                    // Existing fixture dialog handler remains responsible for its single confirmation.
                    alerts.incrementAndGet(); result.confirm(); return true
                }
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val path = request.url.path
                    val content = when (path) {
                        "/order.info.php" -> """<html><body><form id="orderGo" method="POST" action="/togobox/order.reg.php"><span class="date">2026-10-18</span><input name="qty[]" value="1"><input id="totamt" value="5000"><input name="private" value="PRIVATE_ADDRESS"></form></body></html>"""
                        "/togobox/order.reg.php" -> { posts.incrementAndGet(); submitted.set(true); """<html><script>alert('주문이 완료 되었습니다.');location.href='/order.list.php';</script></html>""" }
                        "/togobox/ajax/order.cancel.php" -> { posts.incrementAndGet(); cancelled.set(true); """{"code":"0000","private":"PRIVATE_TOKEN"}""" }
                        else -> historyHtml()
                    }
                    return WebResourceResponse(if (path == "/togobox/ajax/order.cancel.php") "application/json" else "text/html", "UTF-8", content.byteInputStream())
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = capture.pageStarted(url)
                override fun onPageFinished(view: WebView, url: String) = capture.pageFinished(url)
            }
            view.loadUrl("https://dosirak.poswel.co.kr/order.list.php")
        }
        fun awaitDetail(predicate: (SafeDetailObservation) -> Boolean) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < until && diagnostics.details.none(predicate)) Thread.sleep(20)
            assertTrue("Expected typed fixture evidence missing", diagnostics.details.any(predicate))
        }
        try {
            awaitDetail { it.webPhase == DetailWebPhase.HISTORY_BEFORE }
            instrumentation.runOnMainSync { view.loadUrl("https://dosirak.poswel.co.kr/order.info.php") }
            awaitDetail { it.webPhase == DetailWebPhase.CHECKOUT_BEFORE }
            instrumentation.runOnMainSync { view.evaluateJavascript("document.getElementById('orderGo').submit();", null) }
            awaitDetail { it.webPhase == DetailWebPhase.HISTORY_AFTER_ORDER }
            val completed = diagnostics.details.single { it.webPhase == DetailWebPhase.HISTORY_AFTER_ORDER }
            assertEquals(SafeHistoryStatus.ORDERED, completed.history!!.rows.single().status)
            assertEquals(1, completed.history!!.rows.single().quantity)
            assertEquals(5000L, completed.history!!.rows.single().total)
            instrumentation.runOnMainSync { view.evaluateJavascript("cancelFixture();", null) }
            awaitDetail { it.webPhase == DetailWebPhase.HISTORY_AFTER_CANCEL }
            assertEquals(SafeHistoryStatus.CANCELLED, diagnostics.details.single { it.webPhase == DetailWebPhase.HISTORY_AFTER_CANCEL }.history!!.rows.single().status)
            assertTrue(diagnostics.details.single { it.webPhase == DetailWebPhase.HISTORY_AFTER_CANCEL }.history!!.rows.single().cancelTimePresent)
            assertEquals(2, posts.get()); assertEquals(2, alerts.get())
            assertEquals(1, diagnostics.details.count { it.siteAlert == DetailSiteAlert.ORDER_COMPLETED })
            assertEquals(1, diagnostics.details.count { it.siteAlert == DetailSiteAlert.CANCEL_COMPLETED })
            assertFalse(diagnostics.details.toString().contains("PRIVATE_"))
            assertTrue(diagnostics.details.filter { it.siteAlert != null }.all { it.httpStatus == null && it.result == DiagnosticResult.UNKNOWN })
        } finally { instrumentation.runOnMainSync { capture.close(); view.stopLoading(); view.destroy() } }
    }

    @Test fun realWebViewBridgeObservesSyncCancelWithoutChangingItsCallbackAndStopsWhenOff() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val diagnostics = FixtureDiagnostics()
        val loaded = CountDownLatch(1)
        lateinit var view: WebView
        lateinit var capture: WebCapture
        instrumentation.runOnMainSync {
            view = WebView(instrumentation.targetContext)
            view.settings.javaScriptEnabled = true
            view.settings.blockNetworkLoads = true
            capture = WebCapture(view, { fail("Supported fixture unexpectedly unavailable") }, diagnostics)
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val response = if (request.url.path == "/togobox/ajax/order.cancel.php")
                        "application/json" to """{"code":"0000","oNo":"PRIVATE_ID","address":"PRIVATE_ADDRESS"}"""
                    else "text/html" to "<html><body>fixture</body></html>"
                    // Every URL, including accidental off-origin requests, is handled locally.
                    return WebResourceResponse(response.first, "UTF-8", response.second.byteInputStream())
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = capture.pageStarted()
                override fun onPageFinished(view: WebView, url: String) { capture.pageFinished(url); loaded.countDown() }
            }
            view.loadUrl("https://dosirak.poswel.co.kr/order.list.php")
        }
        try {
            assertTrue("Fixture page did not load", loaded.await(10, TimeUnit.SECONDS))
            val callback = CountDownLatch(1)
            var result = ""
            instrumentation.runOnMainSync {
                view.evaluateJavascript("""(function(){var x=new XMLHttpRequest();x.open('POST','/togobox/ajax/order.cancel.php',false);var body='oNo=PRIVATE_ID';var returned=x.send(body);return JSON.stringify({status:x.status,code:JSON.parse(x.responseText).code,returned:returned===undefined});})()""") {
                    result = it; callback.countDown()
                }
            }
            assertTrue(callback.await(10, TimeUnit.SECONDS))
            assertTrue(result.contains("200"))
            assertTrue(result.contains("0000"))
            assertTrue(diagnostics.observed.await(10, TimeUnit.SECONDS))
            val detail = diagnostics.details.single { it.route == DiagnosticRoute.CANCELLATION }
            assertEquals(DetailSource.WEB_OBSERVED, detail.source)
            assertEquals(DiagnosticResult.SUCCEEDED, detail.result)
            assertEquals(200, detail.httpStatus)
            assertFalse(diagnostics.details.toString().contains("PRIVATE_"))
            diagnostics.modes.value = DiagnosticStatus(enabled = false, detailEnabled = false)
            instrumentation.waitForIdleSync()
            val before = diagnostics.details.size
            val offCallback = CountDownLatch(1)
            instrumentation.runOnMainSync {
                view.evaluateJavascript("var x=new XMLHttpRequest();x.open('POST','/togobox/ajax/order.cancel.php',false);x.send('oNo=PRIVATE_ID');") { offCallback.countDown() }
            }
            assertTrue(offCallback.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertEquals(before, diagnostics.details.size)
        } finally {
            instrumentation.runOnMainSync { capture.close(); view.stopLoading(); view.destroy() }
        }
    }

    @Test fun formSubmitIntentSurvivesImmediateNavigationWithoutAnyPostHttpCallback() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val diagnostics = FixtureDiagnostics()
        val loaded = CountDownLatch(1)
        val navigated = CountDownLatch(1)
        lateinit var view: WebView
        lateinit var capture: WebCapture
        instrumentation.runOnMainSync {
            view = WebView(instrumentation.targetContext)
            view.settings.javaScriptEnabled = true
            view.settings.blockNetworkLoads = true
            capture = WebCapture(view, { fail("Supported fixture unexpectedly unavailable") }, diagnostics)
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    WebResourceResponse("text/html", "UTF-8", """<html><body><form id="testForm" method="POST" action="/togobox/order.reg.php"><input name="private" value="PRIVATE_ADDRESS"></form></body></html>""".byteInputStream())
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    capture.pageStarted()
                    if (url.endsWith("/togobox/order.reg.php")) navigated.countDown()
                }
                override fun onPageFinished(view: WebView, url: String) {
                    capture.pageFinished(url)
                    if (url.endsWith("/order.info.php")) loaded.countDown()
                }
            }
            view.loadUrl("https://dosirak.poswel.co.kr/order.info.php")
        }
        try {
            assertTrue("Fixture checkout did not load", loaded.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                // The native HTTP observer is intentionally not wired in this fixture.
                view.evaluateJavascript("document.getElementById('testForm').submit();", null)
            }
            assertTrue("FORM_INTENT was dropped", diagnostics.formObserved.await(10, TimeUnit.SECONDS))
            assertTrue("Form did not navigate", navigated.await(10, TimeUnit.SECONDS))
            val intent = diagnostics.details.single { it.kind == DetailKind.FORM_INTENT }
            assertEquals(DiagnosticRoute.SUBMISSION, intent.route)
            assertEquals(DetailSource.WEB_OBSERVED, intent.source)
            assertEquals(DiagnosticResult.STARTED, intent.result)
            assertNull(intent.httpStatus)
            assertNull(intent.submissionPossible)
            assertEquals(0, diagnostics.details.count { it.kind == DetailKind.HTTP && it.route == DiagnosticRoute.SUBMISSION })
            assertFalse(diagnostics.details.toString().contains("PRIVATE_"))
        } finally {
            instrumentation.runOnMainSync { capture.close(); view.stopLoading(); view.destroy() }
        }
    }

    private class FixtureDiagnostics : DiagnosticController {
        val modes = MutableStateFlow(DiagnosticStatus(enabled = true, detailEnabled = true))
        override val status: StateFlow<DiagnosticStatus> = modes
        val details = CopyOnWriteArrayList<SafeDetailObservation>()
        val observed = CountDownLatch(1)
        val formObserved = CountDownLatch(1)
        private val capture = DetailCapture(DiagnosticRun.newId(), DiagnosticRun.newId(), null, null, null, DiagnosticRun.newId(), 1)
        override fun captureDetail(runId: String, requestId: String?, requestOrdinal: Int?, parentRequestId: String?) = capture
        override fun isDetailCaptureCurrent(capture: DetailCapture) = modes.value.enabled && modes.value.detailEnabled
        override fun recordDetail(capture: DetailCapture, detail: SafeDetailObservation) {
            if (isDetailCaptureCurrent(capture)) {
                details += detail
                if (detail.route == DiagnosticRoute.CANCELLATION) observed.countDown()
                if (detail.kind == DetailKind.FORM_INTENT) formObserved.countDown()
            }
        }
        override fun record(event: DiagnosticEvent) = Unit
        override suspend fun setEnabled(enabled: Boolean) { modes.value = modes.value.copy(enabled = enabled) }
        override suspend fun clear() = Unit
        override suspend fun snapshot(): DiagnosticSnapshot? = null
        override suspend fun cacheSnapshot(snapshot: DiagnosticSnapshot): File? = null
    }
}
