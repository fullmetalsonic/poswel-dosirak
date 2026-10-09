package com.fullmetalsonic.dosirak.ui

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fullmetalsonic.dosirak.web.SiteViewToolbar
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SiteFullscreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fullscreenKeepsLocalWebViewAndExposesFixedNoticeClose() {
        val state = mutableStateOf(UiState(siteRequest = 1))
        val loaded = AtomicBoolean(false)
        val created = AtomicInteger(0)
        val documentLoads = AtomicInteger(0)
        val pageRequests = AtomicInteger(0)
        val closed = AtomicBoolean(false)
        lateinit var web: WebView
        compose.setContent {
            Box(Modifier.width(360.dp).height(600.dp)) {
                LunchApp(state.value, {}, siteContent = { modifier, fullscreen, toggle ->
                    Column(modifier.fillMaxSize()) {
                        SiteViewToolbar(fullscreen, state.value.busy, toggle, {})
                        AndroidView(modifier = Modifier.weight(1f), factory = { context ->
                            WebView(context).also { view ->
                                web = view
                                created.incrementAndGet()
                                view.settings.useWideViewPort = true
                                view.settings.loadWithOverviewMode = true
                                // Client is installed before the only local document load; no live site is created.
                                view.webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                                        // WebView may fetch a favicon after page-finished. Only page/POST replay is relevant here.
                                        if ((request.isForMainFrame && request.url.path != "/fixture-close") ||
                                            request.method == "POST") pageRequests.incrementAndGet()
                                        return WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
                                    }
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        if (request.url.path == "/fixture-close") closed.set(true)
                                        return true
                                    }
                                    override fun onPageFinished(view: WebView, url: String) { loaded.set(true) }
                                }
                                documentLoads.incrementAndGet()
                                view.loadDataWithBaseURL("https://fixture.invalid/", FIXTURE, "text/html", "UTF-8", null)
                            }
                        }, onRelease = { it.destroy() })
                    }
                })
            }
        }
        compose.waitUntil(10_000) { loaded.get() }
        var normalHeight = 0
        var scroll = 0
        var requestCount = 0
        lateinit var original: WebView
        compose.runOnIdle {
            original = web
            normalHeight = web.height
            requestCount = pageRequests.get()
            web.scrollTo(0, 100)
            scroll = web.scrollY
            assertTrue(scroll > 0)
        }
        compose.onNodeWithContentDescription("사이트 전체화면").performClick()
        compose.onNodeWithText("로그인 확인").assertDoesNotExist()
        compose.onNodeWithContentDescription("달력").assertDoesNotExist()
        compose.runOnIdle {
            val density = web.resources.displayMetrics.density
            assertSame(original, web)
            assertEquals(1, created.get())
            assertEquals(scroll, web.scrollY)
            assertTrue(web.height > normalHeight)
            assertTrue("Fixed close link must fit the enlarged viewport", web.height > 502 * density)
            val time = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val touch = MotionEvent.obtain(time, time + 100, action, 185 * density, 478 * density, 0)
                web.dispatchTouchEvent(touch)
                touch.recycle()
            }
        }
        compose.waitUntil(5_000) { closed.get() }
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithContentDescription("전체화면 종료").assertIsNotEnabled()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("로그인 확인").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(busy = false) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("사이트 전체화면").assertExists()
        compose.onNodeWithText("로그인 확인").assertExists()
        compose.runOnIdle {
            assertSame(original, web)
            assertEquals(1, created.get())
            assertEquals(1, documentLoads.get())
            assertEquals(requestCount, pageRequests.get())
            assertEquals(scroll, web.scrollY)
            assertEquals(normalHeight, web.height)
        }
    }

    private companion object {
        val FIXTURE = """<!doctype html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1, user-scalable=no">
            <style>body{margin:0;height:1800px}#notice{position:fixed;top:120px;left:60px;width:250px;height:382px;background:#eee}
            #image{height:314px;background:#ddd}a{display:block;margin-top:20px;height:48px;line-height:48px;text-align:center}</style>
            </head><body><div id="notice"><div id="image"></div><a href="/fixture-close">Close</a></div></body></html>"""
    }
}
