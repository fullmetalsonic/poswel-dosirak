package com.fullmetalsonic.dosirak

import android.app.ActivityManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.domain.ExecutionStatus
import com.fullmetalsonic.dosirak.platform.OrderService
import com.fullmetalsonic.dosirak.runtime.RuntimeProvider
import com.fullmetalsonic.dosirak.site.PoswelClient
import com.fullmetalsonic.dosirak.site.SiteException
import com.fullmetalsonic.dosirak.site.SiteParser
import com.fullmetalsonic.dosirak.ui.UiAction
import com.fullmetalsonic.dosirak.web.NativeWebDiagnostics
import com.fullmetalsonic.dosirak.web.WebEvent
import com.fullmetalsonic.dosirak.web.WebMethod
import com.fullmetalsonic.dosirak.web.WebRoute
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Authenticator
import okhttp3.Request

/** Explicit live login only: no checkout, purchase, cookie clearing, or setting writes. */
@RunWith(AndroidJUnit4::class)
class LiveStoredLoginTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val postAttempted = AtomicBoolean(false)
    private var identityMatch = false
    private var homeGetSeen = false
    private var resultCode = ResultCode.UNKNOWN

    @Test fun storedLoginWithoutPurchaseOrSettingsChange() {
        val optIn = InstrumentationRegistry.getArguments().getString("allowLiveStoredLogin") == "true"
        if (!optIn) report("OPT_IN_REQUIRED")
        assumeTrue("Explicit allowLiveStoredLogin=true is required", optIn)
        val runtime = RuntimeProvider.get(instrumentation.targetContext)
        val settings = runtime.store.loadSettings()
        val credentials = runtime.vault.load() ?: stop("NO_CREDENTIALS")
        if (!settings.useStoredCredentials) stop("STORED_LOGIN_DISABLED")
        val records = runtime.store.loadRecords()
        val overrides = runtime.store.loadOverrides()
        val zone = ZoneId.of("Asia/Seoul")
        val date = LocalDate.now(zone)
        val started = SystemClock.elapsedRealtime()
        fun guard() {
            if (LocalDate.now(zone) != date || LocalTime.now(zone).isBefore(LocalTime.of(8, 0))) stop("OUTSIDE_SAFE_TIME")
            if (SystemClock.elapsedRealtime() - started > 120_000) stop("TIME_LIMIT")
            if (runtime.engine.active.value || serviceActive() || runtime.store.loadRecords().any {
                    it.status == ExecutionStatus.RUNNING || it.status == ExecutionStatus.PREPARING
                }) stop("BUSY")
            if (runtime.store.loadSettings() != settings || runtime.vault.load() != credentials ||
                runtime.store.loadRecords() != records || runtime.store.loadOverrides() != overrides) stop("STATE_CHANGED")
        }
        guard()
        // Clone keeps the application's cookie bridge and network configuration. Observe only request kind.
        val gateway = PoswelClient(runtime.gateway.client.newBuilder().addInterceptor { chain ->
            guard()
            val request = chain.request()
            if (request.method == "POST" && request.url.encodedPath == "/login.check.php") postAttempted.set(true)
            chain.proceed(request)
        }.build())
        try {
            try { gateway.ensureSession(null) }
            catch (failure: SiteException) {
                if (failure.code != "LOGIN_REQUIRED") throw failure
                guard()
                gateway.ensureSession(credentials)
            }
            guard()
            gateway.verifyAccount(credentials.userId)
            identityMatch = true
            resultCode = ResultCode.VERIFIED
            guard()
        } catch (stop: AssumptionViolatedException) { throw stop }
        catch (failure: SiteException) {
            resultCode = ResultCode.entries.firstOrNull { it.name == failure.code } ?: ResultCode.OTHER
            guard()
            stop("LOGIN_FAILED")
        } catch (_: Exception) {
            resultCode = ResultCode.OTHER
            guard()
            stop("LOGIN_FAILED")
        }

        val baseline = NativeWebDiagnostics.sequence()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: AppController
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("controller")
                field.isAccessible = true
                controller = field.get(activity) as AppController
            }
            if (!await(20_000) { !controller.state.value.busy }) stop("BUSY")
            guard()
            // Direct verification must not invoke CheckLogin, clear a block, or re-arm automatic ordering.
            scenario.onActivity { controller.onAction(UiAction.OpenSite("/")) }
            if (!await(45_000) {
                    val events = NativeWebDiagnostics.after(baseline)
                    homeGetSeen = events.any { it.event == WebEvent.REQUEST && it.route == WebRoute.HOME &&
                        it.method == WebMethod.GET && it.mainFrame == true }
                    homeGetSeen && events.any { it.event == WebEvent.PAGE_FINISHED && it.route == WebRoute.HOME }
                }) stop("HOME_LOAD_FAILED")
            val events = NativeWebDiagnostics.after(baseline)
            if (events.any { it.event == WebEvent.SSL_ERROR || it.mainFrame == true &&
                    it.event in setOf(WebEvent.HTTP_ERROR, WebEvent.RESOURCE_ERROR) }) stop("HOME_LOAD_FAILED")
            guard()
            report(if (postAttempted.get()) "STORED_LOGIN_VERIFIED" else "SESSION_ONLY")
        }
    }

    @Test fun inspectIdentityMetadataWithoutLogin() {
        val metadata = Bundle()
        fun reportMetadata(status: String) {
            instrumentation.sendStatus(0, Bundle(metadata).apply { putString("identity_metadata_status", status) })
        }
        fun abortMetadata(status: String): Nothing {
            reportMetadata(status)
            throw AssumptionViolatedException("IDENTITY_$status")
        }
        val optIn = InstrumentationRegistry.getArguments().getString("allowLiveIdentityMetadata") == "true"
        if (!optIn) abortMetadata("OPT_IN_REQUIRED")
        val runtime = RuntimeProvider.get(instrumentation.targetContext)
        val settings = runtime.store.loadSettings()
        val credentials = runtime.vault.load() ?: abortMetadata("NO_CREDENTIALS")
        val records = runtime.store.loadRecords()
        val overrides = runtime.store.loadOverrides()
        val zone = ZoneId.of("Asia/Seoul")
        val date = LocalDate.now(zone)
        val started = SystemClock.elapsedRealtime()
        fun guardMetadata() {
            if (LocalDate.now(zone) != date || LocalTime.now(zone).isBefore(LocalTime.of(8, 0))) abortMetadata("OUTSIDE_SAFE_TIME")
            if (SystemClock.elapsedRealtime() - started > 45_000) abortMetadata("TIME_LIMIT")
            if (runtime.engine.active.value || serviceActive() || runtime.store.loadRecords().any {
                    it.status == ExecutionStatus.RUNNING || it.status == ExecutionStatus.PREPARING
                }) abortMetadata("BUSY")
            if (runtime.store.loadSettings() != settings || runtime.vault.load() != credentials ||
                runtime.store.loadRecords() != records || runtime.store.loadOverrides() != overrides) abortMetadata("STATE_CHANGED")
        }
        guardMetadata()
        metadata.putBoolean("expected_has_outer_whitespace", credentials.userId != credentials.userId.trim())
        metadata.putInt("expected_length", credentials.userId.length)
        val client = runtime.gateway.client.newBuilder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).build()
        try {
            // The sole request is a read-only account-page GET using the existing shared cookie bridge.
            val request = Request.Builder().url("https://dosirak.poswel.co.kr/mod.pass.php").get().build()
            client.newCall(request).execute().use { response ->
                guardMetadata()
                if (response.code in 300..399) abortMetadata("HTTP_REDIRECT")
                if (response.code !in 200..299) abortMetadata("HTTP_ERROR")
                val body = response.body ?: abortMetadata("EMPTY_RESPONSE")
                val source = body.source()
                source.request(2_000_001)
                if (source.buffer.size > 2_000_000) abortMetadata("RESPONSE_TOO_LARGE")
                val doc = SiteParser.document(body.string())
                SiteParser.checkChallenge(doc)
                if (SiteParser.isLogin(doc)) abortMetadata("LOGIN_REQUIRED")
                val identifiers = doc.select("input[name=uid]")
                val input = identifiers.singleOrNull()
                metadata.putInt("identifier_count", identifiers.size)
                metadata.putBoolean("readonly", input?.hasAttr("readonly") == true)
                metadata.putBoolean("type_valid", input?.attr("type")?.equals("text", ignoreCase = true) == true)
                if (input == null || input.id() != "uid" || !input.hasAttr("readonly") || input.hasAttr("disabled") ||
                    !input.attr("type").equals("text", ignoreCase = true) || input.attr("value").trim().isEmpty()) {
                    abortMetadata("INVALID_IDENTIFIER")
                }
                val observed = input.attr("value")
                val siteId = observed.trim()
                metadata.putInt("site_length", observed.length)
                metadata.putInt("site_trimmed_length", siteId.length)
                metadata.putBoolean("equal_after_expected_trim", siteId == credentials.userId.trim())
                metadata.putBoolean("equal_ignore_case", siteId.equals(credentials.userId, ignoreCase = true))
                metadata.putBoolean("equal_trim_ignore_case", siteId.equals(credentials.userId.trim(), ignoreCase = true))
                val expectedId = credentials.userId.trim()
                metadata.putBoolean("expected_is_six_ascii_digits", expectedId.length == 6 && expectedId.all { it in '0'..'9' })
                val bothAsciiDigits = expectedId.isNotEmpty() && siteId.isNotEmpty() &&
                    expectedId.all { it in '0'..'9' } && siteId.all { it in '0'..'9' }
                metadata.putBoolean("both_ascii_digits", bothAsciiDigits)
                metadata.putBoolean("equal_numeric_after_leading_zero_removal", bothAsciiDigits &&
                    expectedId.trimStart('0').ifEmpty { "0" } == siteId.trimStart('0').ifEmpty { "0" })
                metadata.putBoolean("site_has_expected_suffix", expectedId.isNotEmpty() && siteId.endsWith(expectedId))
                val namespacePrefix = if (expectedId.isNotEmpty() && siteId.endsWith(expectedId) &&
                    siteId.length == expectedId.length + 2 && siteId.take(2).matches(Regex("[A-Za-z_:-]{2}"))) {
                    siteId.take(2)
                } else "NON_NAMESPACE"
                metadata.putString("identity_namespace_prefix", namespacePrefix)
                guardMetadata()
                reportMetadata("METADATA_READY")
            }
        } catch (stop: AssumptionViolatedException) { throw stop }
        catch (failure: SiteException) {
            guardMetadata()
            abortMetadata(if (failure.code == "SECURITY_BLOCK_CONTRACT") "SECURITY_BLOCK_CONTRACT" else "CONTRACT_FAILED")
        } catch (_: Exception) {
            guardMetadata()
            abortMetadata("NETWORK_OR_PARSE_FAILED")
        }
    }

    private fun report(outcome: String) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString("live_outcome", outcome)
            putString("result_code", resultCode.name)
            putString("login_POST_attempted", postAttempted.get().toString())
            putString("identity_match", identityMatch.toString())
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
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private enum class ResultCode {
        UNKNOWN, VERIFIED, LOGIN_REQUIRED, LOGIN_FAILED, LOGIN_CONTRACT, SESSION_CONTRACT,
        ACCOUNT_UNVERIFIED, ACCOUNT_MISMATCH, SECURITY_BLOCK_CONTRACT, NETWORK, HTTP, OTHER
    }
}
