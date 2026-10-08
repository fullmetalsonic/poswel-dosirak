package com.fullmetalsonic.dosirak

import android.app.Application
import android.os.UserManager
import com.fullmetalsonic.dosirak.runtime.RuntimeProvider
import kotlinx.coroutines.*

class LunchApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        if (getSystemService(UserManager::class.java).isUserUnlocked) scope.launch {
            runCatching { RuntimeProvider.get(this@LunchApplication).scheduler.reschedule() }
        }
    }
}
