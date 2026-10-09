package com.fullmetalsonic.dosirak.web

import android.util.Log
import java.util.ArrayDeque

data class RecordedWebDiagnostic(val sequence: Long, val diagnostic: WebDiagnostic)

object NativeWebDiagnostics {
    private var sequence = 0L
    private val events = ArrayDeque<RecordedWebDiagnostic>()

    @Synchronized fun sequence(): Long = sequence
    @Synchronized fun after(value: Long): List<WebDiagnostic> = events.filter { it.sequence > value }.map { it.diagnostic }

    @Synchronized fun record(event: WebDiagnostic) {
        events.addLast(RecordedWebDiagnostic(++sequence, event))
        while (events.size > 128) events.removeFirst()
        Log.i("PoswelWebNative", event.safeLogLine())
    }
}
