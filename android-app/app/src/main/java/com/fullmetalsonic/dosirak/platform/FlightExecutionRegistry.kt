package com.fullmetalsonic.dosirak.platform

import java.util.UUID

/** A persisted lease alone is not evidence that its original process still executes work. */
internal class FlightExecutionRegistry(val processNonce: String) {
    private data class Entry(val key: AlarmDispatchKey, val target: Long, var started: Long, var foreground: Boolean = false)
    private val entries = mutableMapOf<String, Entry>()

    @Synchronized fun reserve(token: String, key: AlarmDispatchKey, target: Long, nowElapsed: Long): Boolean {
        entries.entries.removeAll { !withinLease(it.value, nowElapsed) }
        if (token.isBlank() || target <= 0L || nowElapsed < 0L || entries.containsKey(token) ||
            entries.values.any { it.key == key }) return false
        entries[token] = Entry(key, target, nowElapsed)
        return true
    }

    @Synchronized fun live(token: String?, nonce: String?, key: AlarmDispatchKey?, target: Long, nowElapsed: Long): Boolean {
        if (nonce != processNonce || key == null) return false
        val entry = entries[token] ?: return false
        return entry.key == key && entry.target == target && withinLease(entry, nowElapsed)
    }

    @Synchronized fun foreground(token: String, key: AlarmDispatchKey, nowElapsed: Long): Boolean {
        val entry = entries[token] ?: return false
        if (entry.key != key || !withinLease(entry, nowElapsed)) return false
        if (!entry.foreground) entry.started = nowElapsed
        entry.foreground = true
        return true
    }

    @Synchronized fun foregroundEntered(token: String): Boolean = entries[token]?.foreground == true
    @Synchronized fun release(token: String) { entries.remove(token) }

    private fun withinLease(entry: Entry, now: Long) = now >= entry.started &&
        now - entry.started < if (entry.foreground) WarmupAlarmPlanner.SERVICE_MAX_MILLIS else PENDING_MAX_MILLIS

    companion object {
        const val PENDING_MAX_MILLIS = 10_000L
    }
}

internal object ProcessFlightExecution {
    val registry = FlightExecutionRegistry(UUID.randomUUID().toString())
}
