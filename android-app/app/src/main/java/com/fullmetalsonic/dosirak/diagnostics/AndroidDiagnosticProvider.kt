package com.fullmetalsonic.dosirak.diagnostics

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

object AndroidDiagnosticProvider {
    @Volatile private var controller: DiagnosticController? = null
    private val unavailable = object : DiagnosticController {
        override val status: StateFlow<DiagnosticStatus> = MutableStateFlow(DiagnosticStatus(error = DiagnosticStorageError.UNAVAILABLE))
        override fun record(event: DiagnosticEvent) = Unit
        override suspend fun setEnabled(enabled: Boolean) = Unit
        override suspend fun clear() = Unit
        override suspend fun snapshot(): DiagnosticSnapshot? = null
        override suspend fun cacheSnapshot(snapshot: DiagnosticSnapshot): File? = null
    }

    fun get(context: Context): DiagnosticController {
        try {
            if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return unavailable
            controller?.let { return it }
            return synchronized(this) {
                controller ?: create(context.applicationContext).also { controller = it }
            }
        } catch (_: Exception) { return unavailable }
    }

    @Suppress("DEPRECATION")
    private fun create(context: Context): DiagnosticController {
        val preferences = context.getSharedPreferences("analysis_mode", Context.MODE_PRIVATE)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        return DiagnosticStore(
            DiagnosticDisk(File(context.noBackupFilesDir, "analysis"), File(context.cacheDir, "analysis-share")),
            object : DiagnosticPreference {
                override fun readEnabled(): Boolean = preferences.getBoolean("enabled", false)
                override fun writeEnabled(enabled: Boolean): Boolean = preferences.edit().putBoolean("enabled", enabled).commit()
                override fun readLastSaved(): Long? = if (preferences.contains("lastSavedEpochMillis")) preferences.getLong("lastSavedEpochMillis", 0).takeIf { it > 0 } else null
                override fun writeLastSaved(epochMillis: Long?): Boolean = preferences.edit().apply {
                    if (epochMillis == null) remove("lastSavedEpochMillis") else putLong("lastSavedEpochMillis", epochMillis)
                }.commit()
            },
            version,
            elapsedMillis = SystemClock::elapsedRealtime
        )
    }
}
