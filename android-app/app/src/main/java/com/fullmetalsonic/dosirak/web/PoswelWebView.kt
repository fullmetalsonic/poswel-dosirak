package com.fullmetalsonic.dosirak.web

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private const val HOST = "dosirak.poswel.co.kr"
private const val HOME = "https://dosirak.poswel.co.kr/"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PoswelWebView(path: String, busy: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val busyNow by rememberUpdatedState(busy)
    val savedState = rememberSaveable { Bundle() }
    var previousPath by rememberSaveable { mutableStateOf(path) }
    var mainView by remember { mutableStateOf<WebView?>(null) }
    var popup by remember { mutableStateOf<WebView?>(null) }
    var external by remember { mutableStateOf<Uri?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    fun closePopup() {
        popup?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        popup = null
    }

    fun configure(view: WebView, isPopup: Boolean) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.setOnTouchListener { _, _ -> busyNow }
        view.setOnKeyListener { _, _, _ -> busyNow }
        view.setDownloadListener { _, _, _, _, _ -> failure = "파일 다운로드는 외부 브라우저에서 확인하세요." }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return !allowed(request.url)
                if (busyNow) return true
                if (allowed(request.url)) return false
                if (request.url.scheme == "https" || request.url.scheme == "http") external = request.url
                else failure = "지원하지 않는 외부 링크입니다."
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val scheme = request.url.scheme
                val blockedNavigation = request.isForMainFrame && request.url.toString() != "about:blank" && !allowed(request.url)
                return if (blockedNavigation || scheme == "file" || scheme == "content" || scheme == "http")
                    WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), "".byteInputStream()) else null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!isPopup) { loading = true; failure = null }
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                if (!isPopup) { loading = false; canGoBack = view.canGoBack() }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    loading = false
                    failure = when (error.errorCode) {
                        ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT -> "사이트에 연결하지 못했습니다. 인터넷 연결을 확인하세요."
                        else -> "페이지를 불러오지 못했습니다."
                    }
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) { loading = false; failure = "사이트 조회 실패 (HTTP ${response.statusCode})" }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                loading = false
                failure = "사이트 보안 인증서를 확인하지 못했습니다."
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture || busyNow || isPopup || popup != null) return false
                val child = WebView(context)
                configure(child, true)
                popup = child
                (resultMsg.obj as? WebView.WebViewTransport)?.webView = child
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) { if (window === popup) closePopup() }
        }
    }

    DisposableEffect(lifecycle, mainView, popup) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    mainView?.saveState(savedState)
                    mainView?.onPause()
                    popup?.onPause()
                    CookieManager.getInstance().flush()
                }
                Lifecycle.Event.ON_RESUME -> { mainView?.onResume(); popup?.onResume() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose { closePopup(); CookieManager.getInstance().flush() }
    }

    LaunchedEffect(path, mainView, busy) {
        if (path != previousPath && !busyNow) {
            mainView?.loadUrl(targetUrl(path))
            previousPath = path
        }
    }

    BackHandler(enabled = !busy && (popup != null || canGoBack)) {
        val child = popup
        if (child != null) {
            if (child.canGoBack()) child.goBack() else closePopup()
        } else mainView?.goBack()
    }

    Column(Modifier.fillMaxSize()) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        failure?.let { message ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(message, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { failure = null; (popup ?: mainView)?.reload() }, enabled = !busy) { Text("다시 시도") }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { viewContext ->
                WebView(viewContext).also { view ->
                    configure(view, false)
                    mainView = view
                    if (view.restoreState(savedState) == null) view.loadUrl(targetUrl(path))
                    canGoBack = view.canGoBack()
                }
            }, update = { view ->
                view.isEnabled = !busy
                view.importantForAccessibility = if (busy) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                if (busy) {
                    view.clearFocus()
                    context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
                }
            }, onRelease = { view ->
                view.saveState(savedState)
                CookieManager.getInstance().flush()
                view.stopLoading()
                view.destroy()
                mainView = null
            })
            if (busy) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.18f))
                .pointerInput(Unit) { detectTapGestures { } })
        }
    }

    popup?.let { child ->
        Dialog(onDismissRequest = { if (!busyNow) closePopup() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = false)) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                TextButton(onClick = { closePopup() }, enabled = !busy) { Text("닫기") }
                Box(Modifier.weight(1f)) {
                    AndroidView(factory = { child }, modifier = Modifier.fillMaxSize(), update = { view ->
                        view.isEnabled = !busy
                        view.importantForAccessibility = if (busy) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        if (busy) { view.clearFocus(); context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0) }
                    })
                    if (busy) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } })
                }
            }
        }
    }

    external?.let { uri ->
        AlertDialog(onDismissRequest = { external = null }, title = { Text("외부 브라우저 열기") },
            text = { Text(uri.host.orEmpty()) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    .onFailure { failure = "이 링크를 열 수 있는 브라우저가 없습니다." }
                external = null
            }) { Text("열기") } },
            dismissButton = { TextButton(onClick = { external = null }) { Text("취소") } })
    }
}

private fun allowed(uri: Uri): Boolean = uri.scheme == "https" && uri.host.equals(HOST, ignoreCase = true) &&
    uri.userInfo == null && (uri.port == -1 || uri.port == 443)

private fun targetUrl(path: String): String {
    val uri = Uri.parse(if (path.startsWith("/")) HOME.dropLast(1) + path else path)
    return if (allowed(uri)) uri.toString() else HOME
}
