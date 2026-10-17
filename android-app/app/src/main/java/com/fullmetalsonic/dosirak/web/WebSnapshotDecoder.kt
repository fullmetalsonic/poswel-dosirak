package com.fullmetalsonic.dosirak.web

import com.fullmetalsonic.dosirak.diagnostics.*
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.LocalDate

internal object WebSnapshotDecoder {
    fun decode(token: String, sequence: Int, type: WebCaptureMessage.Type, input: JsonElement?): WebCaptureMessage? = runCatching {
        val obj = input?.asJsonObject ?: return null
        if (type == WebCaptureMessage.Type.CHECKOUT) {
            require(obj.keySet() == setOf("date", "quantity", "total"))
            return WebCaptureMessage(token, sequence, type, business = SafeBusinessObservation(
                targetDate = date(obj["date"]), quantity = number(obj["quantity"], 10000)?.toInt(), total = number(obj["total"], 1_000_000_000)))
        }
        require(obj.keySet() == setOf("table", "body", "all", "rows"))
        val table = bool(obj, "table"); val body = bool(obj, "body"); val all = bool(obj, "all")
        val rawRows = obj["rows"].asJsonArray
        require(rawRows.size() <= 32 && (!body || table) && (!all || body))
        val rows = rawRows.map { raw ->
            val row = raw.asJsonObject
            require(row.keySet() == setOf("date", "quantity", "total", "state", "cancelCell", "cancelTime", "cancelLink", "handler", "rating"))
            val state = row["state"].takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val status = SafeHistoryStatus.entries.firstOrNull { it.name == state } ?: error("state")
            val handler = bool(row, "handler"); val link = bool(row, "cancelLink")
            require(!handler || link)
            SafeHistoryRow(date(row["date"]), number(row["quantity"], 10000)?.toInt(), number(row["total"], 1_000_000_000),
                status, bool(row, "cancelCell"), bool(row, "cancelTime"), link,
                if (handler) SafeHistoryHandler.JS_ORDER_CXL else null, bool(row, "rating"))
        }
        WebCaptureMessage(token, sequence, type, history = SafeHistoryStructure(table, body, rows.size, all, rows, !all))
    }.getOrNull()

    private fun bool(obj: JsonObject, key: String): Boolean {
        val value = obj[key]; require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean); return value.asBoolean
    }
    private fun number(value: JsonElement, maximum: Long): Long? {
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.toString().matches(Regex("0|[1-9][0-9]{0,9}")))
        return value.asLong.also { require(it in 0..maximum) }
    }
    private fun date(value: JsonElement): LocalDate? {
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.matches(Regex("20[0-9]{2}-[0-9]{2}-[0-9]{2}|2100-[0-9]{2}-[0-9]{2}")))
        return LocalDate.parse(value.asString)
    }
}
