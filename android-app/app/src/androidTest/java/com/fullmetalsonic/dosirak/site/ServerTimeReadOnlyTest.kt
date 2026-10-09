package com.fullmetalsonic.dosirak.site

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fullmetalsonic.dosirak.data.WebCookieBridge
import com.fullmetalsonic.dosirak.domain.ServerOrderClock
import com.fullmetalsonic.dosirak.platform.IsolatedWarmupProbeRunner
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Explicit opt-in: existing session, one read-only time probe, no runtime or purchase API. */
@RunWith(AndroidJUnit4::class)
class ServerTimeReadOnlyTest {
    @Test fun existingSessionProvidesFreshServerTimeWithoutAnyPost() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("poswelLiveTimeProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue("Use the isolated runner", instrumentation is IsolatedWarmupProbeRunner)
        assertEquals(android.app.Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        val cookies = WebCookieBridge()
        var requests = 0
        val client = OkHttpClient.Builder().cookieJar(cookies)
            .addInterceptor { chain ->
                val request = chain.request()
                check(request.method == "GET" && request.url.scheme == "https" &&
                    request.url.host == "dosirak.poswel.co.kr" && request.url.encodedPath in setOf("/", "/index.php") &&
                    request.url.query == null) { "Read-only probe refused an unexpected request" }
                requests++
                chain.proceed(request)
            }
            .addNetworkInterceptor(cookies.responseInterceptor)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build()
        val clock = ServerOrderClock(elapsedMillis = SystemClock::elapsedRealtime)
        val gateway = PoswelClient(client, clock = clock, elapsedMillis = SystemClock::elapsedRealtime)
        val sample = gateway.probeServerTime()
        val accepted = clock.accept(sample)
        Log.i("POSWEL_TIME_PROBE", "GET_ONLY requests=$requests rtt_ms=${sample.responseFinishedElapsedMillis - sample.requestStartedElapsedMillis} accepted=$accepted")
        assertEquals(1, requests)
        assertTrue("Fresh site time did not satisfy the timing policy", accepted)
        assertNotNull(clock.bounds())
    }
}
