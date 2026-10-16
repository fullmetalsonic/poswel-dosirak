package com.fullmetalsonic.dosirak.platform

import com.fullmetalsonic.dosirak.diagnostics.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeDiagnosticsTest {
    @Test fun offDoesNotEvaluateEnvironmentOrNotificationObservation() {
        var evaluated = false
        DiagnosticSink.NONE.runtimeDetail {
            evaluated = true
            error("environment lookup must not run while off")
        }
        assertFalse(evaluated)
    }

    @Test fun diagnosticFailuresDoNotChangeOrderResultOrContext() {
        val previous = DiagnosticRun.newId()
        val reference = RuntimeDiagnosticReference.from(DiagnosticRun.newId())
        val broken = object : DiagnosticSink {
            override fun record(event: DiagnosticEvent) = Unit
            override fun captureDetail(runId: String, requestId: String?, requestOrdinal: Int?, parentRequestId: String?): DetailCapture? =
                error("diagnostic failure")
        }
        DiagnosticRun.threadLocal.set(previous)
        try {
            val result = withRuntimeDiagnosticRun(reference) {
                assertEquals(reference.runId, DiagnosticRun.currentId())
                broken.runtimeDetail { error("must not evaluate after capture fails") }
                "unchanged order result"
            }
            assertEquals("unchanged order result", result)
            assertEquals(previous, DiagnosticRun.currentId())
        } finally { DiagnosticRun.threadLocal.remove() }
    }

    @Test fun invalidReferencesAreNeverRetainedAsCorrelationOrOrderInputs() {
        val first = RuntimeDiagnosticReference.from("private-account@example.invalid", "device-secret")
        val second = RuntimeDiagnosticReference.from("private-account@example.invalid", "device-secret")
        assertTrue(DiagnosticRun.valid(first.runId))
        assertNotEquals(first.runId, second.runId)
        assertNull(first.parentRunId)
        val validParent = DiagnosticRun.newId()
        val validRun = DiagnosticRun.newId()
        assertEquals(RuntimeDiagnosticReference(validRun, validParent), RuntimeDiagnosticReference.from(validRun, validParent))
    }

    @Test fun nestedSchedulingContextRestoresServiceReferenceEvenOnFailure() {
        val service = RuntimeDiagnosticReference.from(DiagnosticRun.newId())
        val scheduler = RuntimeDiagnosticReference.from(DiagnosticRun.newId(), service.runId)
        withRuntimeDiagnosticRun(service) {
            try { withRuntimeDiagnosticRun(scheduler) { error("scheduling failure") } } catch (_: IllegalStateException) { }
            assertEquals(service.runId, DiagnosticRun.currentId())
        }
    }
}
