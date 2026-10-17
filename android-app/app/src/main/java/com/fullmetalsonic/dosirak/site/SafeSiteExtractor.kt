package com.fullmetalsonic.dosirak.site

import com.fullmetalsonic.dosirak.diagnostics.*
import com.google.gson.JsonElement
import okhttp3.HttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.time.LocalDate

/** Reads existing parser objects synchronously; only allowlisted immutable shapes leave this class. */
internal class SafeSiteExtractor(private val nanoTime: () -> Long = System::nanoTime) {
    private val runTimes = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 16
    }

    internal class Budget(private val now: () -> Long, private val previous: Long) {
        private val started = now()
        var visited = 0; private set
        var fields = 0; private set
        private var bytes = 4096
        val omissions = linkedSetOf(DetailOmission.PERSONAL_FIELDS_EXCLUDED)
        var unknown = 0
        val elapsed: Long get() = (now() - started).coerceAtLeast(0)
        val partial: Boolean get() = omissions.any { it !in setOf(DetailOmission.PERSONAL_FIELDS_EXCLUDED, DetailOmission.UNKNOWN_FIELDS_EXCLUDED) }
        fun visit(depth: Int): Boolean {
            if (!available()) return false
            if (depth > 20) { omissions.add(DetailOmission.DEPTH_LIMIT); return false }
            if (visited >= 500) { omissions.add(DetailOmission.NODE_LIMIT); return false }
            visited++
            return true
        }
        fun available(): Boolean {
            if (previous + elapsed >= 50_000_000L) { omissions.add(DetailOmission.RUN_TIME_LIMIT); return false }
            if (elapsed >= 10_000_000L) { omissions.add(DetailOmission.TIME_LIMIT); return false }
            return true
        }
        fun reserve(amount: Int): Boolean {
            if (!available()) return false
            if (bytes + amount > 32 * 1024) { omissions.add(DetailOmission.BYTE_LIMIT); return false }
            bytes += amount
            return true
        }
        fun field(): Boolean {
            if (fields >= 64) { omissions.add(DetailOmission.FIELD_LIMIT); return false }
            if (!reserve(640)) return false
            fields++
            return true
        }
        fun unknown(): Int {
            unknown++
            omissions.add(DetailOmission.UNKNOWN_FIELDS_EXCLUDED)
            return unknown
        }
    }

    @Synchronized
    fun document(doc: Document, base: HttpUrl, sink: DiagnosticSink, capture: DetailCapture?, route: DiagnosticRoute,
        historyTable: Element? = null, historyBody: Element? = null, historyRows: List<Element> = emptyList()) =
        extract(sink, capture, route) { budget ->
            // HISTORY reuses the parser's table/row references before decorative page nodes consume the budget.
            val history = if (route == DiagnosticRoute.HISTORY) history(historyTable, historyBody, historyRows, budget) else null
            val historyObservation = history?.let {
                budget.available()
                val partial = it.partial || budget.partial
                SafeDetailObservation(DetailKind.STRUCTURE, route = route,
                    history = it.copy(allRowsScanned = it.allRowsScanned && !partial, partial = partial),
                    coverage = if (partial) DetailCoverage.PARTIAL else DetailCoverage.COMPLETE,
                    omissions = budget.omissions.toSet(), visitedNodes = budget.visited, extractionNanos = budget.elapsed)
            }
            val forms = mutableListOf<Form>()
            val loose = Form(null, DetailMethod.OTHER)
            fun walk(node: Node, depth: Int, owner: Form) {
                if (node === historyTable) return
                if (!budget.visit(depth)) return
                var form = owner
                if (node is Element) {
                    if (node.tagName() == "form") {
                        if (!budget.reserve(1024)) return
                        form = Form(action(node.safeAttr("action"), base), method(node.safeAttr("method")))
                        forms.add(form)
                    }
                    if (node.tagName() in setOf("input", "select", "textarea", "button") && budget.field()) {
                        val rawName = node.safeAttr("name")
                        val name = fieldName(rawName) ?: if (!node.hasAttr("name")) fieldId(node.safeAttr("id")) else null
                        val type = fieldType(node)
                        val quantities = mutableListOf<Int>()
                        var options = 0
                        if (node.tagName() == "select") {
                            fun optionWalk(part: Node, optionDepth: Int) {
                                if (!budget.visit(optionDepth)) return
                                if (part is Element && part.tagName() == "option") {
                                    if (options >= 32) { budget.omissions.add(DetailOmission.OPTION_LIMIT); return }
                                    options++
                                    if (name == SafeFieldName.QTY || name == SafeFieldName.QUANTITY) {
                                        part.safeAttr("value")?.toIntOrNull()?.takeIf { it in 0..10000 }?.let(quantities::add)
                                    }
                                }
                                var child = 0
                                while (child < part.childNodeSize() && options < 32 && budget.available()) optionWalk(part.childNode(child++), optionDepth + 1)
                                if (child < part.childNodeSize() && options >= 32) budget.omissions.add(DetailOmission.OPTION_LIMIT)
                            }
                            var child = 0
                            while (child < node.childNodeSize() && options < 32 && budget.available()) optionWalk(node.childNode(child++), depth + 1)
                            if (child < node.childNodeSize() && options >= 32) budget.omissions.add(DetailOmission.OPTION_LIMIT)
                        }
                        val quantityField = name in setOf(SafeFieldName.QTY, SafeFieldName.QUANTITY) &&
                            type in setOf(SafeFieldType.SELECT, SafeFieldType.NUMBER, SafeFieldType.TEXT)
                        val submitField = type in setOf(SafeFieldType.SUBMIT, SafeFieldType.BUTTON)
                        form.fields.add(SafeFormField(name, if (name == null) budget.unknown() else null, type,
                            node.hasAttr("required"), node.hasAttr("readonly"),
                            if (quantityField) node.safeAttr("min")?.toIntOrNull()?.takeIf { it in 0..10000 } else null,
                            if (quantityField) node.safeAttr("max")?.toIntOrNull()?.takeIf { it in 0..10000 } else null,
                            options, quantities.toList(), disabled = node.hasAttr("disabled"),
                            actionRoute = if (submitField) action(node.safeAttr("formaction"), base) else null,
                            actionMethod = if (submitField) node.safeAttr("formmethod")?.let(::method) else null))
                        if (node.tagName() == "select") return
                    }
                }
                var child = 0
                while (child < node.childNodeSize() && budget.available() && budget.visited < 500) walk(node.childNode(child++), depth + 1, form)
                if (child < node.childNodeSize() && budget.visited >= 500) budget.omissions.add(DetailOmission.NODE_LIMIT)
            }
            walk(doc, 0, loose)
            if (loose.fields.isNotEmpty()) forms.add(loose)
            val observations = forms.map { form -> SafeDetailObservation(DetailKind.STRUCTURE, route = route,
                form = SafeFormStructure(form.route, form.method, form.fields.toList(),
                    accountFieldPresent = form.fields.any { it.name == SafeFieldName.UID },
                    omittedUnknownCount = budget.unknown, partial = budget.partial)) }
            if (historyObservation == null) observations else listOf(historyObservation) + observations.ifEmpty {
                // Keep the page/form traversal gap separate even when no form was reached before its limit.
                if (budget.partial) listOf(SafeDetailObservation(DetailKind.STRUCTURE, route = route)) else emptyList()
            }
        }

    private fun history(table: Element?, body: Element?, sourceRows: List<Element>, budget: Budget): SafeHistoryStructure {
        val rows = mutableListOf<SafeHistoryRow>()
        var examined = 0
        if (table == null || body == null) budget.omissions.add(DetailOmission.STRUCTURE_NOT_EXTRACTED)
        if (!budget.reserve(512)) return SafeHistoryStructure(table != null, body != null, partial = true)
        for (row in sourceRows) {
            if (examined >= 32) { budget.omissions.add(DetailOmission.HISTORY_ROW_LIMIT); break }
            if (!budget.available() || !budget.reserve(640)) break
            examined++
            val text = mutableMapOf<String, StringBuilder>()
            var cancelCellPresent = false
            var cancelLinkPresent = false
            var ratingLinkPresent = false
            var handler: SafeHistoryHandler? = null
            var complete = true
            var cellIndex = -1
            fun walk(node: Node, depth: Int, field: String? = null) {
                if (!budget.visit(depth)) { complete = false; return }
                var part = field
                if (node is Element) {
                    if (node.parent() === row && node.tagName() == "td") {
                        cellIndex++
                        part = when {
                            node.hasClass("date") -> "date"
                            node.hasClass("qtt") -> "quantity"
                            node.hasClass("pay") -> "total"
                            node.hasClass("state") -> "status"
                            node.hasClass("btn-cancle") || cellIndex == 6 -> "cancel"
                            node.hasClass("point") || cellIndex == 7 -> "rating"
                            else -> null
                        }
                        if (part == "cancel") cancelCellPresent = true
                    }
                    if (node.tagName() == "a") {
                        val allowedHandler = listOf("href", "onclick").any { attr ->
                            node.safeAttr(attr)?.matches(CANCEL_HANDLER) == true
                        }
                        if (part == "cancel" || allowedHandler) {
                            cancelLinkPresent = true
                            if (allowedHandler) handler = SafeHistoryHandler.JS_ORDER_CXL
                        }
                        if (part == "rating") ratingLinkPresent = true
                    }
                } else if (node is TextNode && part in setOf("date", "quantity", "total", "status", "cancel")) {
                    val value = node.wholeText
                    val output = text.getOrPut(requireNotNull(part)) { StringBuilder() }
                    if (value.length <= 128 && output.length + value.length <= 128) output.append(value).append(' ')
                }
                var child = 0
                while (child < node.childNodeSize() && budget.available() && budget.visited < 500) walk(node.childNode(child++), depth + 1, part)
                if (child < node.childNodeSize()) {
                    complete = false
                    if (budget.visited >= 500) budget.omissions.add(DetailOmission.NODE_LIMIT)
                }
            }
            walk(row, 0)
            fun value(name: String) = text[name]?.toString()?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
            val date = DATE.matchEntire(value("date"))?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.takeIf { it.year in 2000..2100 }
            val quantity = value("quantity").takeIf { it.matches(Regex("[0-9]{1,5}")) }?.toIntOrNull()?.takeIf { it in 0..10000 }
            val total = value("total").takeIf { it.matches(Regex("[0-9]+(?:,[0-9]{3})*(?:\\s*원)?")) }
                ?.removeSuffix("원")?.trim()?.replace(",", "")?.toLongOrNull()?.takeIf { it in 0..1_000_000_000 }
            val status = when (value("status")) {
                "주문완료" -> SafeHistoryStatus.ORDERED
                "배송완료" -> SafeHistoryStatus.DELIVERED
                "취소", "주문취소" -> SafeHistoryStatus.CANCELLED
                else -> SafeHistoryStatus.UNKNOWN
            }
            rows.add(SafeHistoryRow(date, quantity, total, status, cancelCellPresent,
                value("cancel").matches(CANCEL_TIME), cancelLinkPresent, handler, ratingLinkPresent))
            if (!complete) break
        }
        val all = body != null && examined == sourceRows.size && !budget.partial
        return SafeHistoryStructure(table != null, body != null, examined, all, rows.toList(), !all)
    }

    @Synchronized
    fun json(root: JsonElement, sink: DiagnosticSink, capture: DetailCapture?, route: DiagnosticRoute) =
        extract(sink, capture, route) { budget ->
            fun walk(value: JsonElement, depth: Int, key: SafeJsonKey? = null, slot: Int? = null): SafeJsonNode? {
                if (!budget.visit(depth) || !budget.reserve(384)) return null
                val type = when {
                    value.isJsonNull -> SafeJsonType.NULL
                    value.isJsonObject -> SafeJsonType.OBJECT
                    value.isJsonArray -> SafeJsonType.ARRAY
                    value.asJsonPrimitive.isBoolean -> SafeJsonType.BOOLEAN
                    value.asJsonPrimitive.isNumber -> SafeJsonType.NUMBER
                    else -> SafeJsonType.STRING
                }
                val children = mutableListOf<SafeJsonNode>()
                var count: Int? = null
                if (value.isJsonObject) {
                    count = value.asJsonObject.size()
                    val iterator = value.asJsonObject.entrySet().iterator()
                    while (iterator.hasNext() && children.size < 64 && budget.available() && budget.visited < 500) {
                        val entry = iterator.next()
                        val safeKey = if (entry.key.length <= 128) jsonKey(entry.key) else null
                        val child = walk(entry.value, depth + 1, safeKey, if (safeKey == null) budget.unknown() else null) ?: break
                        children.add(child)
                    }
                    if (iterator.hasNext() && budget.visited >= 500) budget.omissions.add(DetailOmission.NODE_LIMIT)
                    if (iterator.hasNext() && children.size >= 64) budget.omissions.add(DetailOmission.FIELD_LIMIT)
                } else if (value.isJsonArray) {
                    count = value.asJsonArray.size()
                    var index = 0
                    while (index < count && children.size < 64 && budget.available() && budget.visited < 500) {
                        val child = walk(value.asJsonArray[index++], depth + 1) ?: break
                        children.add(child)
                    }
                    if (index < count && budget.visited >= 500) budget.omissions.add(DetailOmission.NODE_LIMIT)
                    if (index < count && children.size >= 64) budget.omissions.add(DetailOmission.FIELD_LIMIT)
                }
                return SafeJsonNode(key, slot, type, count, children.toList())
            }
            val shape = walk(root, 0) ?: SafeJsonNode()
            listOf(SafeDetailObservation(DetailKind.STRUCTURE, route = route,
                json = SafeJsonStructure(shape, budget.unknown, budget.partial)))
        }

    private fun extract(sink: DiagnosticSink, capture: DetailCapture?, route: DiagnosticRoute,
        block: (Budget) -> List<SafeDetailObservation>) {
        if (capture == null) return
        try {
            if (!sink.isDetailCaptureCurrent(capture)) return
            val previous = runTimes[capture.runId] ?: 0
            val budget = Budget(nanoTime, previous)
            val details = if (budget.available()) block(budget) else emptyList()
            val elapsed = budget.elapsed
            budget.available()
            runTimes[capture.runId] = (previous + elapsed).coerceAtMost(50_000_000L)
            val coverage = if (budget.partial) DetailCoverage.PARTIAL else DetailCoverage.COMPLETE
            if (sink.isDetailCaptureCurrent(capture)) {
                val output = details.ifEmpty { listOf(SafeDetailObservation(DetailKind.STRUCTURE, route = route)) }
                output.forEach { observation ->
                    val saved = if (observation.history != null) observation else observation.copy(
                        coverage = coverage, omissions = budget.omissions.toSet(),
                        form = observation.form?.copy(partial = budget.partial), json = observation.json?.copy(partial = budget.partial),
                        extractionNanos = elapsed, visitedNodes = budget.visited)
                    sink.safeDetail(capture, saved)
                }
            }
        } catch (_: Throwable) {
            sink.safeDetail(capture, SafeDetailObservation(DetailKind.STRUCTURE, route = route,
                coverage = DetailCoverage.REACHED_NOT_EXTRACTED, omissions = setOf(DetailOmission.EXTRACTION_FAILED)))
        }
    }

    private class Form(val route: DiagnosticRoute?, val method: DetailMethod, val fields: MutableList<SafeFormField> = mutableListOf())
    private fun Element.safeAttr(name: String): String? = attr(name).takeIf { it.length <= 128 && it.isNotEmpty() }
    private fun method(value: String?): DetailMethod = when (value?.lowercase()) { "get" -> DetailMethod.GET; "post" -> DetailMethod.POST; else -> DetailMethod.OTHER }
    private fun fieldType(element: Element): SafeFieldType = when (element.tagName()) {
        "select" -> SafeFieldType.SELECT
        "button" -> when (element.safeAttr("type")?.lowercase()) {
            null, "submit" -> SafeFieldType.SUBMIT; "button" -> SafeFieldType.BUTTON; else -> SafeFieldType.OTHER
        }
        else -> when (element.safeAttr("type")?.lowercase()) {
            null, "text" -> SafeFieldType.TEXT; "password" -> SafeFieldType.PASSWORD; "hidden" -> SafeFieldType.HIDDEN
            "number" -> SafeFieldType.NUMBER; "checkbox" -> SafeFieldType.CHECKBOX; "radio" -> SafeFieldType.RADIO
            "submit" -> SafeFieldType.SUBMIT; "button" -> SafeFieldType.BUTTON; "date" -> SafeFieldType.DATE; else -> SafeFieldType.OTHER
        }
    }
    private fun fieldName(name: String?): SafeFieldName? = when (name) {
        "uid", "uid[]", "od_uid", "od_jikbun" -> SafeFieldName.UID
        "pwd" -> SafeFieldName.PWD
        "qty[]" -> SafeFieldName.QTY
        "od_quntity" -> SafeFieldName.QUANTITY
        "od_date" -> SafeFieldName.ORDER_DATE
        "od_menu_price", "priceEach", "price" -> SafeFieldName.PRICE
        "orderAmt", "totamt" -> SafeFieldName.TOTAL
        "od_menu_kind", "od_menu_name" -> SafeFieldName.MENU
        "course", "delivery_nm", "delivery_det", "rv_name", "rv_phone", "loc", "sec", "lc", "sc" -> SafeFieldName.DELIVERY
        "payroll" -> SafeFieldName.PAYMENT
        "utk", "od_name", "od_hp", "it", "allDel", "s", "e" -> SafeFieldName.OTHER_KNOWN
        else -> null
    }
    private fun jsonKey(name: String): SafeJsonKey? = when (name) {
        "code" -> SafeJsonKey.CODE; "result" -> SafeJsonKey.RESULT; "success" -> SafeJsonKey.SUCCESS
        "data" -> SafeJsonKey.DATA; "list" -> SafeJsonKey.LIST; "items" -> SafeJsonKey.ITEMS
        "me_date" -> SafeJsonKey.DATE; "od_date" -> SafeJsonKey.ORDER_DATE; "me_quantity" -> SafeJsonKey.QUANTITY
        "me_price", "od_menu_price" -> SafeJsonKey.PRICE; "total" -> SafeJsonKey.TOTAL; "amount" -> SafeJsonKey.AMOUNT
        "me_menu" -> SafeJsonKey.MENU; "message" -> SafeJsonKey.MESSAGE; else -> null
    }

    private fun fieldId(id: String?): SafeFieldName? = when {
        id == "loc" || id == "sec" -> SafeFieldName.DELIVERY
        id == "go-pay" || id == "deliveryCxl" -> SafeFieldName.SUBMIT
        id == "orderAmt" || id == "totamt" -> SafeFieldName.TOTAL
        id != null && Regex("qty_?[0-9]+").matches(id) -> SafeFieldName.QTY
        id != null && Regex("priceEach_?[0-9]+").matches(id) -> SafeFieldName.PRICE
        id != null && Regex("(?:price|priceSum_)[0-9]+").matches(id) -> SafeFieldName.TOTAL
        else -> null
    }

    private fun action(value: String?, base: HttpUrl): DiagnosticRoute? {
        if (value == null) return null
        val target = base.resolve(value) ?: return null
        if (target.scheme != base.scheme || target.host != base.host || target.port != base.port ||
            target.username.isNotEmpty() || target.password.isNotEmpty() || target.query != null || target.fragment != null) return null
        return route(target.encodedPath).takeIf { it != DiagnosticRoute.OTHER }
    }

    companion object {
        private val DATE = Regex("([0-9]{4}-[0-9]{2}-[0-9]{2})(?:\\s+[0-9]{2}:[0-9]{2}(?::[0-9]{2})?)?")
        private val CANCEL_TIME = Regex("(?:(?:[0-9]{2}|[0-9]{4})[-.][0-9]{2}[-.][0-9]{2}\\s+)?[0-9]{2}:[0-9]{2}(?::[0-9]{2})?")
        private val CANCEL_HANDLER = Regex("(?:javascript:\\s*)?jsOrderCxl\\(\\s*(['\"]?)[0-9]+\\1\\s*\\)\\s*;?")
        fun route(path: String): DiagnosticRoute = when (path) {
            "/" -> DiagnosticRoute.HOME; "/login.check.php" -> DiagnosticRoute.LOGIN; "/mod.pass.php" -> DiagnosticRoute.PROFILE
            "/order.list.php" -> DiagnosticRoute.HISTORY; "/togobox/order.temp.php" -> DiagnosticRoute.TEMPORARY_ORDER
            "/togobox/get.order.temp.php" -> DiagnosticRoute.TEMPORARY_CART; "/order.info.php" -> DiagnosticRoute.CHECKOUT
            "/togobox/order.reg.php" -> DiagnosticRoute.SUBMISSION; else -> DiagnosticRoute.OTHER
        }
    }
}
