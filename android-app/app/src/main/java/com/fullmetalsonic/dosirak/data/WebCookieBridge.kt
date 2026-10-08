package com.fullmetalsonic.dosirak.data

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebCookieBridge : CookieJar {
    private val main = Handler(Looper.getMainLooper())
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        main.post { result = runCatching(block); latch.countDown() }
        if (!latch.await(8, TimeUnit.SECONDS)) throw IOException("사이트 세션 동기화 실패")
        return result!!.getOrThrow()
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!trusted(url)) return emptyList()
        val header = onMain { CookieManager.getInstance().getCookie(url.toString()) }.orEmpty()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
    // Retain raw Set-Cookie attributes instead of reconstructing them through OkHttp Cookie.
    val responseInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val url = response.request.url
        if (trusted(url)) {
            for (header in response.headers("Set-Cookie")) {
                val latch = CountDownLatch(1)
                onMain { CookieManager.getInstance().setCookie(url.toString(), header) { latch.countDown() } }
                if (!latch.await(8, TimeUnit.SECONDS)) { response.close(); throw IOException("사이트 쿠키 저장 실패") }
            }
            onMain { CookieManager.getInstance().flush() }
        }
        response
    }
    private fun trusted(url: HttpUrl) = url.scheme == "https" && url.host == "dosirak.poswel.co.kr"
}
