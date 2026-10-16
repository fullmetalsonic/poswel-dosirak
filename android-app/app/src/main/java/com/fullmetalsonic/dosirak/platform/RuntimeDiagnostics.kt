package com.fullmetalsonic.dosirak.platform

import android.content.Intent
import com.fullmetalsonic.dosirak.diagnostics.*

/** These identifiers are diagnostic-only and must never participate in dispatch guards. */
internal data class RuntimeDiagnosticReference(val runId: String, val parentRunId: String? = null,
    val linkMissing: Boolean = false) {
    val omissions: Set<DetailOmission> get() = if (linkMissing) setOf(DetailOmission.PARTIAL_START) else emptySet()
    fun putInto(intent: Intent): Intent = intent.putExtra(EXTRA_RUN, runId).putExtra(EXTRA_PARENT, parentRunId)

    companion object {
        const val EXTRA_RUN = "diagnostic_run_id"
        const val EXTRA_PARENT = "diagnostic_parent_run_id"

        fun from(runId: String?, parentRunId: String? = null): RuntimeDiagnosticReference =
            RuntimeDiagnosticReference(runId?.takeIf(DiagnosticRun::valid) ?: DiagnosticRun.newId(),
                parentRunId?.takeIf(DiagnosticRun::valid), runId == null || !DiagnosticRun.valid(runId))

        fun from(intent: Intent?): RuntimeDiagnosticReference = from(
            runCatching { intent?.getStringExtra(EXTRA_RUN) }.getOrNull(),
            runCatching { intent?.getStringExtra(EXTRA_PARENT) }.getOrNull())
    }
}

internal inline fun <T> withRuntimeDiagnosticRun(reference: RuntimeDiagnosticReference, action: () -> T): T {
    val previous = DiagnosticRun.threadLocal.get()
    DiagnosticRun.threadLocal.set(reference.runId)
    return try { action() } finally { DiagnosticRun.threadLocal.set(previous) }
}

internal inline fun DiagnosticSink.runtimeDetail(runId: String? = DiagnosticRun.currentId(),
    observation: () -> SafeDetailObservation) {
    try {
        val capture = captureDetail(runId ?: DiagnosticRun.newId()) ?: return
        safeDetail(capture, observation())
    } catch (_: Throwable) { }
}
