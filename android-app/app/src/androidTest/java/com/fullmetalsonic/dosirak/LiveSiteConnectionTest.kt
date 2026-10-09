package com.fullmetalsonic.dosirak

import android.app.ActivityManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.domain.ExecutionStatus
import com.fullmetalsonic.dosirak.platform.OrderService
import com.fullmetalsonic.dosirak.runtime.RuntimeProvider
import com.fullmetalsonic.dosirak.ui.UiAction
import com.fullmetalsonic.dosirak.web.*
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveSiteConnectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var loginVerified = false
    private var homeGetSeen = false
    private var loginCode = LoginCheckCode.UNKNOWN

    @Test fun nativeLoginThenSingleHomeGet() {
        val optedIn = InstrumentationRegistry.getArguments().getString("allowLiveSiteCheck") == "true"
        if (!optedIn) report("OPT_IN_REQUIRED")
        assumeTrue("Explicit allowLiveSiteCheck=true is required", optedIn)
        val context = instrumentation.targetContext
        val runtime = RuntimeProvider.get(context)
        val original = runtime.store.loadSettings()
        val originalRecords = runtime.store.loadRecords()
        val originalOverrides = runtime.store.loadOverrides()
        if (original.masterEnabled) stop("MASTER_ON")
        if (!runtime.vault.hasCredentials() || runCatching { runtime.vault.load()?.userId.isNullOrBlank() }.getOrDefault(true)) stop("NO_CREDENTIALS")
        if (runtime.engine.active.value || originalRecords.any { it.status == ExecutionStatus.RUNNING || it.status == ExecutionStatus.PREPARING } || serviceActive()) stop("BUSY")
        val baseline = NativeWebDiagnostics.sequence()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: AppController
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("controller")
                field.isAccessible = true
                controller = field.get(activity) as AppController
            }
            if (!await(20_000) { !controller.state.value.busy && controller.state.value.accountLabel.isNotBlank() }) stop("BUSY")
            if (runtime.store.loadSettings().masterEnabled) stop("MASTER_ON")
            if (runtime.engine.active.value || serviceActive() || runtime.store.loadRecords().any {
                it.status == ExecutionStatus.RUNNING || it.status == ExecutionStatus.PREPARING
            }) stop("BUSY")
            scenario.onActivity { controller.onAction(UiAction.CheckLogin) }
            if (!await(120_000) { !controller.state.value.busy }) {
                loginCode = controller.loginDiagnosticCode
                stop("LOGIN_FAILED")
            }
            loginCode = controller.loginDiagnosticCode
            loginVerified = controller.state.value.loginVerified
            if (!loginVerified || loginCode != LoginCheckCode.VERIFIED) stop("LOGIN_FAILED")
            val after = runtime.store.loadSettings()
            if (after.accountGeneration != original.accountGeneration || after.masterEnabled ||
                after.liveScope != original.liveScope || after.displayPriceRiskAccepted != original.displayPriceRiskAccepted ||
                runtime.store.loadRecords() != originalRecords || runtime.store.loadOverrides() != originalOverrides ||
                (original.liveBlockedReason?.startsWith("SECURITY_BLOCK_CONTRACT") == true && after.liveBlockedReason != original.liveBlockedReason)) stop("STATE_CHANGED")
            scenario.onActivity { controller.onAction(UiAction.ClearMessage) }
            val finished = await(60_000) {
                val events = NativeWebDiagnostics.after(baseline)
                homeGetSeen = events.any { it.event == WebEvent.REQUEST && it.route == WebRoute.HOME && it.method == WebMethod.GET && it.mainFrame == true }
                homeGetSeen && events.any { it.event == WebEvent.PAGE_FINISHED && it.route == WebRoute.HOME && it.mainFrame == true }
            }
            val events = NativeWebDiagnostics.after(baseline)
            if (!finished || events.any { it.event == WebEvent.SSL_ERROR ||
                (it.mainFrame == true && it.event in setOf(WebEvent.HTTP_ERROR, WebEvent.RESOURCE_ERROR)) }) stop("HOME_LOAD_FAILED")
            var visibleFrame = false
            scenario.onActivity { visibleFrame = hasVisibleWebView(it.window.decorView) }
            if (!visibleFrame) stop("HOME_LOAD_FAILED")
            if (events.count { it.event == WebEvent.HOME_GET } != 1) stop("HOME_LOAD_FAILED")
            report("CONNECTED")
        }
    }

    private fun report(outcome: String) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString("live_outcome", outcome)
            putString("login_code", loginCode.name)
            putString("login_verified", loginVerified.toString())
            putString("home_get_seen", homeGetSeen.toString())
            putString("webview_login_visual_check", "REQUIRED")
        })
    }

    private fun stop(outcome: String): Nothing {
        report(outcome)
        throw AssumptionViolatedException("LIVE_$outcome")
    }

    @Suppress("DEPRECATION")
    private fun serviceActive(): Boolean = instrumentation.targetContext.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == OrderService::class.java.name }

    private fun await(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun hasVisibleWebView(view: View): Boolean {
        if (view is WebView && view.isShown && view.width > 0 && view.height > 0) return true
        if (view is ViewGroup) return (0 until view.childCount).any { hasVisibleWebView(view.getChildAt(it)) }
        return false
    }
}
