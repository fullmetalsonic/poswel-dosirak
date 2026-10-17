package com.fullmetalsonic.dosirak.web

import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import java.io.StringReader
import java.net.URI
import com.fullmetalsonic.dosirak.diagnostics.SafeBusinessObservation
import com.fullmetalsonic.dosirak.diagnostics.SafeHistoryStructure

/** Untrusted page observations. These must never authorize an order or cancellation. */
internal data class WebCaptureMessage(
    val token: String,
    val sequence: Int,
    val type: Type,
    val route: Route? = null,
    val method: String? = null,
    val status: Int? = null,
    val durationMillis: Long? = null,
    val success: Boolean? = null,
    val history: SafeHistoryStructure? = null,
    val business: SafeBusinessObservation? = null
) {
    enum class Type { READY, XHR, FETCH, FORM, HISTORY, CHECKOUT }
    enum class Route { CANCEL, TEMP, CART, SUBMIT }
}

internal object WebCaptureDecoder {
    const val MAX_MESSAGE_CHARS = 16_384
    const val MAX_EVENTS = 256
    private val keys = setOf("v", "token", "seq", "type", "route", "method", "status", "duration", "success", "snapshot")
    private val tokenPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun trustedOrigin(origin: String): Boolean = runCatching {
        val uri = URI(origin)
        uri.scheme == "https" && uri.host == "dosirak.poswel.co.kr" &&
            uri.port in setOf(-1, 443) && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in setOf("", "/")
    }.getOrDefault(false)

    fun trustedPage(page: String?): Boolean = runCatching {
        val uri = URI(page ?: return false)
        uri.scheme == "https" && uri.host == "dosirak.poswel.co.kr" &&
            uri.port in setOf(-1, 443) && uri.rawUserInfo == null
    }.getOrDefault(false)

    fun decode(text: String?): WebCaptureMessage? = runCatching {
        if (text == null || text.length !in 1..MAX_MESSAGE_CHARS) return null
        val reader = JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }
        // Read key names separately so duplicate keys cannot overwrite an earlier value.
        val seen = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (key !in keys || !seen.add(key)) return null
            fun skipUnique(depth: Int) {
                require(depth <= 5)
                when (reader.peek()) {
                    com.google.gson.stream.JsonToken.BEGIN_OBJECT -> {
                        val names = mutableSetOf<String>(); reader.beginObject()
                        while (reader.hasNext()) { require(names.add(reader.nextName())); skipUnique(depth + 1) }
                        reader.endObject()
                    }
                    com.google.gson.stream.JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray(); var count = 0
                        while (reader.hasNext()) { require(++count <= 32); skipUnique(depth + 1) }; reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            skipUnique(0)
        }
        reader.endObject()
        if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) return null
        val obj = JsonParser.parseString(text).asJsonObject
        fun string(key: String): String? = obj[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        fun integer(key: String, range: LongRange): Long? {
            val value = obj[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.toString() ?: return null
            if (!value.matches(Regex("0|[1-9][0-9]{0,8}"))) return null
            return value.toLongOrNull()?.takeIf { it in range }
        }
        if (integer("v", 1L..1L) != 1L) return null
        val token = string("token")?.takeIf(tokenPattern::matches) ?: return null
        val seq = integer("seq", 0L..MAX_EVENTS.toLong())?.toInt() ?: return null
        val type = WebCaptureMessage.Type.entries.firstOrNull { it.name == string("type") } ?: return null
        if (type == WebCaptureMessage.Type.READY) {
            if (seen != setOf("v", "token", "seq", "type") || seq != 0) return null
            return WebCaptureMessage(token, seq, type)
        }
        if (type == WebCaptureMessage.Type.HISTORY || type == WebCaptureMessage.Type.CHECKOUT) {
            if (seen != setOf("v", "token", "seq", "type", "snapshot") || seq < 1) return null
            return WebSnapshotDecoder.decode(token, seq, type, obj["snapshot"])
        }
        if ("snapshot" in seen) return null
        if (!seen.containsAll(setOf("route", "method", "status", "duration")) || seq < 1) return null
        val route = WebCaptureMessage.Route.entries.firstOrNull { it.name == string("route") } ?: return null
        val method = string("method")?.takeIf { it in setOf("GET", "POST", "OTHER") } ?: return null
        val status = integer("status", 0L..599L)?.toInt() ?: return null
        if (status in 1..99) return null
        val duration = integer("duration", 0L..600_000L) ?: return null
        val success = if (obj.has("success")) {
            val value = obj["success"]
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean || route != WebCaptureMessage.Route.CANCEL) return null
            value.asBoolean
        } else null
        if (type == WebCaptureMessage.Type.FORM && (route != WebCaptureMessage.Route.SUBMIT || method != "POST" || status != 0 || success != null)) return null
        if (type != WebCaptureMessage.Type.FORM && route == WebCaptureMessage.Route.SUBMIT) return null
        if (route == WebCaptureMessage.Route.CANCEL && method != "POST") return null
        WebCaptureMessage(token, seq, type, route, method, status, duration, success)
    }.getOrNull()
}
