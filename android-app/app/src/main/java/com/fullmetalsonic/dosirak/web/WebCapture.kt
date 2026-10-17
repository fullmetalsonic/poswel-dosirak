package com.fullmetalsonic.dosirak.web

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebMessageCompat
import com.fullmetalsonic.dosirak.diagnostics.AndroidDiagnosticProvider
import com.fullmetalsonic.dosirak.diagnostics.DetailCapture
import com.fullmetalsonic.dosirak.diagnostics.DetailCoverage
import com.fullmetalsonic.dosirak.diagnostics.DetailKind
import com.fullmetalsonic.dosirak.diagnostics.DetailMethod
import com.fullmetalsonic.dosirak.diagnostics.DetailOmission
import com.fullmetalsonic.dosirak.diagnostics.DetailSource
import com.fullmetalsonic.dosirak.diagnostics.DiagnosticResult
import com.fullmetalsonic.dosirak.diagnostics.DiagnosticRoute
import com.fullmetalsonic.dosirak.diagnostics.DiagnosticRun
import com.fullmetalsonic.dosirak.diagnostics.DiagnosticStage
import com.fullmetalsonic.dosirak.diagnostics.DiagnosticController
import com.fullmetalsonic.dosirak.diagnostics.SafeDetailObservation
import com.fullmetalsonic.dosirak.diagnostics.DetailWebPhase
import com.fullmetalsonic.dosirak.diagnostics.DetailSiteAlert
import com.fullmetalsonic.dosirak.diagnostics.safeDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID
import java.net.URI

/** A log-only, origin-scoped observer. It has no purchase/cancel/settings methods. */
internal class WebCapture(private val view: WebView, private val unsupported: () -> Unit,
    private val diagnostics: DiagnosticController = AndroidDiagnosticProvider.get(view.context)) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    private var script: ScriptHandler? = null
    private var bridgeInstalled = false
    @Volatile private var closed = false
    private var finishedPage: String? = null
    private var capture: DetailCapture? = null
    private var token: String? = null
    private val gate = WebCaptureGate()
    private val lifecycle = WebCaptureLifecycle()
    private var missingBeforeRecorded = false
    private var observedSegmentId: String? = null
    private var unsupportedRecorded = false
    private val runId = DiagnosticRun.newId()
    private val namespace = UUID.randomUUID().toString().replace("-", "")
    private val bridgeName = "PoswelCapture$namespace"
    private val controlName = "__poswelCaptureControl$namespace"
    private val scriptText by lazy {
        view.context.assets.open("web-capture.js").bufferedReader().use { it.readText() }
            .replace("PoswelCapture", bridgeName).replace("__poswelCaptureControl", controlName)
    }

    init {
        if (supported) {
            runCatching {
                WebViewCompat.addWebMessageListener(view, bridgeName, ORIGINS) { _, message, sourceOrigin, isMainFrame, _ ->
                    runCatching {
                        if (message.type == WebMessageCompat.TYPE_STRING) receive(message.data, sourceOrigin, isMainFrame)
                    }
                }
                bridgeInstalled = true
            }
        }
        scope.launch { diagnostics.status.collectLatest { refresh() } }
    }

    fun pageStarted(url: String? = view.url) {
        lifecycle.started(url)
        finishedPage = null
        invalidate()
    }

    fun pageFinished(url: String) {
        if (url != view.url || !WebCaptureDecoder.trustedPage(url)) return
        finishedPage = url
        refresh()
    }

    private fun enabled(): Boolean = diagnostics.status.value.let { it.enabled && it.detailEnabled }

    private fun refresh() {
        if (closed) return
        if (!enabled()) {
            lifecycle.resetObservations()
            missingBeforeRecorded = false
            observedSegmentId = null
            invalidate()
            runCatching { script?.remove() }
            script = null
            unsupportedRecorded = false
            return
        }
        if (!supported || !bridgeInstalled) {
            recordUnsupported()
            return
        }
        if (script == null) {
            script = runCatching { WebViewCompat.addDocumentStartJavaScript(view, scriptText, ORIGINS) }.getOrNull()
            if (script == null) {
                recordUnsupported()
                return
            }
        }
        val page = finishedPage ?: return
        if (page != view.url || !WebCaptureDecoder.trustedPage(page)) return
        val previous = capture
        if (previous != null && diagnostics.isDetailCaptureCurrent(previous) && token != null) return
        invalidate()
        capture = diagnostics.captureDetail(runId) ?: return
        if (observedSegmentId != null && observedSegmentId != capture?.captureSegmentId) {
            lifecycle.resetObservations()
            missingBeforeRecorded = false
        }
        observedSegmentId = capture?.captureSegmentId
        token = UUID.randomUUID().toString()
        gate.activate(token!!, page)
        // Also installs on an already-open document when the user enables collection there.
        // No HTTP request, credential read, form operation, or site handler is invoked.
        view.evaluateJavascript("$scriptText\n;window.$controlName && window.$controlName(\"$token\");", null)
    }

    private fun invalidate() {
        val wasActive = token != null
        token = null
        capture = null
        gate.invalidate()
        if (!closed && wasActive) runCatching { view.evaluateJavascript("window.$controlName && window.$controlName(null);", null) }
    }

    private fun recordUnsupported() {
        if (!unsupportedRecorded) {
            unsupportedRecorded = true
            diagnostics.safeDetail(diagnostics.captureDetail(runId), SafeDetailObservation(
                kind = DetailKind.STRUCTURE, stage = DiagnosticStage.WEB, coverage = DetailCoverage.NOT_REACHED,
                omissions = setOf(DetailOmission.UNSUPPORTED_SCHEMA), source = DetailSource.WEB_OBSERVED))
        }
        if (finishedPage != null) unsupported()
    }

    private fun receive(text: String?, origin: Uri, mainFrame: Boolean) {
        if (closed) return
        val active = capture ?: return
        val message = gate.accept(text, origin.toString(), mainFrame, view.url,
            enabled(), diagnostics.isDetailCaptureCurrent(active)) ?: return
        if (message.type == WebCaptureMessage.Type.HISTORY && WebCaptureLifecycle.path(view.url) != "/order.list.php") return
        if (message.type == WebCaptureMessage.Type.CHECKOUT && WebCaptureLifecycle.path(view.url) != "/order.info.php") return
        if (message.type == WebCaptureMessage.Type.FORM) lifecycle.orderIntent()
        if (message.route == WebCaptureMessage.Route.CANCEL) lifecycle.cancelObserved()
        if (message.type == WebCaptureMessage.Type.CHECKOUT && !lifecycle.historySeen && !missingBeforeRecorded) {
            missingBeforeRecorded = true
            diagnostics.safeDetail(active, SafeDetailObservation(DetailKind.STRUCTURE, route = DiagnosticRoute.HISTORY,
                stage = DiagnosticStage.WEB, coverage = DetailCoverage.NOT_REACHED, webPhase = DetailWebPhase.HISTORY_BEFORE,
                generation = lifecycle.generation, source = DetailSource.WEB_OBSERVED))
        }
        val observation = WebCaptureObservation.fromMessage(message) ?: return
        diagnostics.safeDetail(active, observation.copy(generation = lifecycle.generation,
            webPhase = when (message.type) {
                WebCaptureMessage.Type.HISTORY -> lifecycle.historyPhase()
                WebCaptureMessage.Type.FORM -> DetailWebPhase.ORDER_INTENT
                else -> observation.webPhase
            }))
    }

    /** Log only. The caller must preserve WebChromeClient's original dialog handling. */
    fun jsAlert(url: String?, message: String?) {
        if (closed || !enabled()) return
        val active = diagnostics.captureDetail(runId) ?: return
        if (observedSegmentId != null && observedSegmentId != active.captureSegmentId) {
            lifecycle.resetObservations(); missingBeforeRecorded = false
        }
        observedSegmentId = active.captureSegmentId
        val matched = lifecycle.alert(url, view.url, message) ?: return
        diagnostics.safeDetail(active, SafeDetailObservation(DetailKind.BUSINESS,
            route = if (matched == DetailSiteAlert.CANCEL_COMPLETED) DiagnosticRoute.CANCELLATION else DiagnosticRoute.SUBMISSION,
            stage = DiagnosticStage.WEB, result = DiagnosticResult.UNKNOWN,
            coverage = DetailCoverage.PARTIAL,
            omissions = if (matched == DetailSiteAlert.UNKNOWN_OMITTED) setOf(DetailOmission.MESSAGE_OMITTED) else emptySet(),
            completionAlertMatched = matched == DetailSiteAlert.ORDER_COMPLETED,
            siteAlert = matched, webPhase = if (matched == DetailSiteAlert.CANCEL_COMPLETED) DetailWebPhase.CANCEL_RESULT else DetailWebPhase.ORDER_RESULT,
            generation = lifecycle.generation, source = DetailSource.WEB_OBSERVED))
    }

    /** Called on WebView's I/O thread; do not inspect the view or touch any request/body. */
    fun mainFrameRequest(url: String, method: String) {
        if (closed || method != "POST" || !enabled()) return
        val submission = runCatching {
            val uri = URI(url)
            WebCaptureDecoder.trustedPage(url) && uri.path == "/togobox/order.reg.php"
        }.getOrDefault(false)
        if (!submission) return
        val requestCapture = diagnostics.captureDetail(runId) ?: return
        diagnostics.safeDetail(requestCapture, SafeDetailObservation(
            kind = DetailKind.HTTP, route = DiagnosticRoute.SUBMISSION, stage = DiagnosticStage.WEB,
            result = DiagnosticResult.STARTED, coverage = DetailCoverage.HTTP_ONLY,
            omissions = setOf(DetailOmission.PERSONAL_FIELDS_EXCLUDED, DetailOmission.STRUCTURE_NOT_EXTRACTED),
            method = DetailMethod.POST, source = DetailSource.WEB_OBSERVED))
    }

    fun close() {
        if (closed) return
        if (enabled()) lifecycle.pendingHistory()?.let { phase ->
            diagnostics.safeDetail(diagnostics.captureDetail(runId), SafeDetailObservation(DetailKind.STRUCTURE,
                route = DiagnosticRoute.HISTORY, stage = DiagnosticStage.WEB, coverage = DetailCoverage.NOT_REACHED,
                webPhase = phase, generation = lifecycle.generation, source = DetailSource.WEB_OBSERVED))
        }
        invalidate()
        closed = true
        scope.cancel()
        runCatching { script?.remove() }
        script = null
        if (bridgeInstalled) runCatching { WebViewCompat.removeWebMessageListener(view, bridgeName) }
    }

    private companion object {
        val ORIGINS = setOf("https://dosirak.poswel.co.kr")
    }
}
