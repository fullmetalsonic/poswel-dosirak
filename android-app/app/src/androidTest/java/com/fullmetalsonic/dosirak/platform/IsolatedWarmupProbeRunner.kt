package com.fullmetalsonic.dosirak.platform

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

class IsolatedWarmupProbeRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)

    override fun onCreate(arguments: Bundle?) {
        val selected = arguments?.getString("class").orEmpty().split(',').map { it.substringBefore('#') }
        require(selected.isNotEmpty() && selected.all { it in ALLOWED_TESTS }) { "Isolated runner only permits explicit safe probe tests" }
        super.onCreate(arguments)
    }

    companion object {
        private val ALLOWED_TESTS = setOf(
            "com.fullmetalsonic.dosirak.platform.WarmupProbeTest",
            "com.fullmetalsonic.dosirak.platform.SchedulerIntegrationTest",
            "com.fullmetalsonic.dosirak.data.ReleaseMigrationTest"
        )
    }
}
