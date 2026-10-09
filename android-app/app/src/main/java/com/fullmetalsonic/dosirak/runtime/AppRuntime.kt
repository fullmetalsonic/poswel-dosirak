package com.fullmetalsonic.dosirak.runtime

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import com.fullmetalsonic.dosirak.domain.ServerOrderClock
import com.fullmetalsonic.dosirak.data.*
import com.fullmetalsonic.dosirak.platform.*
import com.fullmetalsonic.dosirak.site.PoswelClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppRuntime(context: Context) {
    val store = AppStore(context)
    val vault = CredentialVault(context)
    val onboarding = OnboardingStore(context).apply { initialize(store.hasSavedData() || vault.hasCredentials()) }
    val notifier = OrderNotifier(context)
    val environment = EnvironmentInspector(context)
    val orderClock = ServerOrderClock(elapsedMillis = SystemClock::elapsedRealtime)
    val scheduler = ReservationScheduler(context, store, orderClock)
    val backgroundCheck = BackgroundCheckScheduler(context, store)
    private val changes = MutableStateFlow(0L)
    val updates = changes.asStateFlow()
    private val cookies = WebCookieBridge()
    val gateway = PoswelClient(OkHttpClient.Builder().cookieJar(cookies)
        .addNetworkInterceptor(cookies.responseInterceptor)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build(), clock = orderClock, elapsedMillis = SystemClock::elapsedRealtime)
    val engine = OrderEngine(store, vault, gateway, notifier, ::changed, orderClock)
    fun changed() { changes.value = changes.value + 1 }
}

object RuntimeProvider {
    @Volatile private var runtime: AppRuntime? = null
    fun get(context: Context): AppRuntime {
        check(context.getSystemService(UserManager::class.java).isUserUnlocked) { "첫 잠금 해제가 필요합니다." }
        return runtime ?: synchronized(this) {
            runtime ?: AppRuntime(context.applicationContext).also { runtime = it }
        }
    }
}
