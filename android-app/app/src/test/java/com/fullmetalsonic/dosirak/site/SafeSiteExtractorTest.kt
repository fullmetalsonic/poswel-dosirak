package com.fullmetalsonic.dosirak.site

import com.fullmetalsonic.dosirak.diagnostics.*
import com.google.gson.GsonBuilder
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SafeSiteExtractorTest {
    private val base = "https://dosirak.poswel.co.kr/".toHttpUrl()
    private fun Gson() = GsonBuilder().registerTypeAdapter(LocalDate::class.java,
        JsonSerializer<LocalDate> { date, _, _ -> JsonPrimitive(date.toString()) }).create()
    private class Sink : DiagnosticSink {
        val details = mutableListOf<SafeDetailObservation>()
        var enabled = true
        val capture = DetailCapture(DiagnosticRun.newId(), DiagnosticRun.newId(), DiagnosticRun.newId(), 1, null, DiagnosticRun.newId(), 0)
        override fun record(event: DiagnosticEvent) = Unit
        override fun isDetailCaptureCurrent(capture: DetailCapture): Boolean = enabled
        override fun recordDetail(capture: DetailCapture, detail: SafeDetailObservation) { details.add(detail) }
    }

    @Test fun unknownRequiredControlsAndSafeButtonContractSurviveWithoutPrivateNamesValuesOrMutation() {
        val doc = Jsoup.parse("""<form action='/togobox/order.reg.php' method='post'>
            <input name='uid' value='PRIVATE_ACCOUNT' readonly><input name='pwd' type='password' value='PRIVATE_PASSWORD'>
            <input name='utk' value='PRIVATE_TOKEN' type='hidden'>
            <input name='PRIVATE_DYNAMIC_NAME' value='PRIVATE_ADDRESS' type='checkbox' required>
            <select name='qty[]' min='1' max='5'><option value='1'>PRIVATE_LABEL</option><option value='2'>two</option></select>
            <select name='delivery_nm'><option value='PRIVATE_DELIVERY'>PRIVATE_MENU</option></select>
            <button type='submit' disabled formaction='/togobox/order.reg.php' formmethod='post'>PRIVATE_BUTTON</button>
            </form><script>PRIVATE_SCRIPT</script>""")
        val before = doc.outerHtml()
        val sink = Sink()
        SafeSiteExtractor { 0L }.document(doc, base, sink, sink.capture, DiagnosticRoute.CHECKOUT)
        assertEquals(before, doc.outerHtml())
        val form = sink.details.single().form!!
        assertEquals(DiagnosticRoute.SUBMISSION, form.actionRoute)
        assertEquals(DetailMethod.POST, form.method)
        val unknown = form.fields.single { it.type == SafeFieldType.CHECKBOX }
        assertNull(unknown.name); assertNotNull(unknown.unknownFieldSlot); assertTrue(unknown.required)
        val qty = form.fields.single { it.name == SafeFieldName.QTY }
        assertEquals(1, qty.minimum); assertEquals(5, qty.maximum); assertEquals(listOf(1, 2), qty.quantityOptions)
        val button = form.fields.single { it.type == SafeFieldType.SUBMIT }
        assertTrue(button.disabled); assertEquals(DiagnosticRoute.SUBMISSION, button.actionRoute)
        assertEquals(DetailMethod.POST, button.actionMethod)
        assertTrue(form.fields.single { it.name == SafeFieldName.DELIVERY }.quantityOptions.isEmpty())
        assertFalse(Gson().toJson(sink.details).contains("PRIVATE_"))
    }

    @Test fun jsonUnknownKeysAndNestedShapeSurviveWithoutAnyScalarValues() {
        val json = JsonParser.parseString("""{"code":"PRIVATE_CODE","data":[{"PRIVATE_KEY":{"PRIVATE_TOKEN":["PRIVATE_VALUE",4,true,null]},"me_menu":"PRIVATE_MENU"}]}""")
        val before = json.toString()
        val sink = Sink()
        SafeSiteExtractor { 0L }.json(json, sink, sink.capture, DiagnosticRoute.TEMPORARY_CART)
        assertEquals(before, json.toString())
        val shape = sink.details.single().json!!
        assertTrue(shape.omittedUnknownCount >= 2)
        val data = shape.root.children.single { it.key == SafeJsonKey.DATA }
        assertEquals(SafeJsonType.ARRAY, data.type); assertEquals(1, data.count)
        assertTrue(data.children.single().children.any { it.unknownKeySlot != null && it.type == SafeJsonType.OBJECT })
        assertFalse(Gson().toJson(sink.details).contains("PRIVATE_"))
    }

    @Test fun wideDeepAndHugeScalarInputsRemainBounded() {
        val sink = Sink()
        val doc = Jsoup.parse("<form>" + "<input name='PRIVATE_${"x".repeat(10000)}' value='${"S".repeat(100000)}' required>".repeat(100) + "</form>")
        SafeSiteExtractor { 0L }.document(doc, base, sink, sink.capture, DiagnosticRoute.CHECKOUT)
        assertTrue(sink.details.single().form!!.fields.size <= 64)
        assertTrue(sink.details.single().coverage == DetailCoverage.PARTIAL)
        assertTrue(Gson().toJson(sink.details).toByteArray().size <= 32768)
        assertFalse(Gson().toJson(sink.details).contains("PRIVATE_"))
        sink.details.clear()
        val nested = JsonParser.parseString("[".repeat(40) + "0" + "]".repeat(40))
        SafeSiteExtractor { 0L }.json(nested, sink, sink.capture, DiagnosticRoute.TEMPORARY_CART)
        assertTrue(sink.details.single().omissions.contains(DetailOmission.DEPTH_LIMIT))
        sink.details.clear()
        val wide = JsonParser.parseString("[" + List(10000) { "0" }.joinToString(",") + "]")
        SafeSiteExtractor { 0L }.json(wide, sink, sink.capture, DiagnosticRoute.TEMPORARY_CART)
        assertTrue(sink.details.single().coverage == DetailCoverage.PARTIAL)
        assertTrue(Gson().toJson(sink.details).toByteArray().size <= 32768)
        assertTrue(sink.details.single().visitedNodes!! <= 500)
    }

    @Test fun optionsAndExternalActionsNeverCopyUnsafeValues() {
        val sink = Sink()
        val doc = Jsoup.parse("<form action='https://private.example/path?secret=PRIVATE_QUERY'><select name='qty[]'>" +
            (1..100).joinToString("") { "<option value='$it'>PRIVATE_TEXT</option>" } + "</select></form>")
        SafeSiteExtractor { 0L }.document(doc, base, sink, sink.capture, DiagnosticRoute.CHECKOUT)
        val result = sink.details.single()
        assertNull(result.form!!.actionRoute)
        assertEquals(32, result.form.fields.single().optionCount)
        assertEquals((1..32).toList(), result.form.fields.single().quantityOptions)
        assertTrue(result.omissions.contains(DetailOmission.OPTION_LIMIT))
        assertFalse(Gson().toJson(sink.details).contains("PRIVATE_"))
    }

    @Test fun responseAndRunBudgetsStopCooperativelyAndDisabledCaptureDoesNoTraversal() {
        val sink = Sink()
        var tick = 0L
        val extractor = SafeSiteExtractor { tick.also { tick += 1_000_000 } }
        val doc = Jsoup.parse("<form>" + "<input required>".repeat(100) + "</form>")
        repeat(7) { extractor.document(doc, base, sink, sink.capture, DiagnosticRoute.HOME) }
        assertTrue(sink.details.any { DetailOmission.TIME_LIMIT in it.omissions })
        assertTrue(sink.details.any { DetailOmission.RUN_TIME_LIMIT in it.omissions })
        assertTrue(sink.details.all { it.visitedNodes!! < 100 })
        val count = sink.details.size
        val oldTick = tick
        sink.enabled = false
        extractor.document(doc, base, sink, sink.capture, DiagnosticRoute.HOME)
        assertEquals(count, sink.details.size); assertEquals(oldTick, tick)
    }

    @Test fun turningOffDuringExtractionDiscardsTheSnapshot() {
        val sink = Sink()
        var calls = 0
        val extractor = SafeSiteExtractor { calls++; if (calls > 5) sink.enabled = false; 0L }
        extractor.document(Jsoup.parse("<form><input name='qty[]'></form>"), base, sink, sink.capture, DiagnosticRoute.CHECKOUT)
        assertTrue(sink.details.isEmpty())
    }

    private fun historyRow(cancel: String, state: String = "주문완료", quantity: String = "2", total: String = "10,000") = """
        <tr><td class='date'>2026-10-17<br><small>06:00</small></td><td class='menu'>PRIVATE_MENU</td>
        <td class='qtt'>$quantity</td><td class='deliver'>PRIVATE_ADDRESS PRIVATE_PHONE</td>
        <td class='state'>$state</td><td class='pay'>$total</td><td class='btn-cancle'>$cancel</td>
        <td class='point'><a href='/PRIVATE_RATING?token=PRIVATE_TOKEN'>PRIVATE_RATING</a></td></tr>"""

    private fun captureHistory(html: String, extractor: SafeSiteExtractor = SafeSiteExtractor { 0L }): Pair<org.jsoup.nodes.Document, Sink> {
        val doc = Jsoup.parse(html)
        val sink = Sink()
        val table = doc.getElementById("dataTable")
        val body = table?.selectFirst("tbody")
        extractor.document(doc, base, sink, sink.capture, DiagnosticRoute.HISTORY, table, body, body?.select("tr")?.toList().orEmpty())
        return doc to sink
    }

    @Test fun historyIsCapturedBeforeFiveHundredDecorativeNodesWithNoPrivateValuesOrDomMutation() {
        val html = "<form><input name='qty[]'></form><div>" + "<i>PRIVATE_NOISE</i>".repeat(600) + "</div><table id='dataTable'><tbody>" +
            historyRow("<a href=\"javascript:jsOrderCxl('987654321')\">주문취소</a>") +
            historyRow("26.10.17 06:14", "취소", "", "") +
            historyRow("<a onclick=\"PRIVATE_UNKNOWN('PRIVATE_ID')\">주문취소</a>") + "</tbody></table>"
        val before = Jsoup.parse(html).outerHtml()
        val (doc, sink) = captureHistory(html)
        assertEquals(before, doc.outerHtml())
        val result = sink.details.single { it.history != null }
        val history = requireNotNull(result.history)
        assertTrue(history.tablePresent); assertTrue(history.bodyPresent); assertTrue(history.allRowsScanned)
        assertEquals(3, history.rowsExamined)
        assertEquals(LocalDate.of(2026, 10, 17), history.rows[0].date)
        assertEquals(2, history.rows[0].quantity); assertEquals(10000L, history.rows[0].total)
        assertEquals(SafeHistoryStatus.ORDERED, history.rows[0].status)
        assertTrue(history.rows[0].cancelCellPresent); assertTrue(history.rows[0].cancelLinkPresent)
        assertEquals(SafeHistoryHandler.JS_ORDER_CXL, history.rows[0].cancelHandler)
        assertTrue(history.rows[0].ratingLinkPresent)
        assertEquals(SafeHistoryStatus.CANCELLED, history.rows[1].status)
        assertTrue(history.rows[1].cancelTimePresent); assertFalse(history.rows[1].cancelLinkPresent)
        assertTrue(history.rows[2].cancelLinkPresent); assertNull(history.rows[2].cancelHandler)
        assertEquals(DetailCoverage.COMPLETE, result.coverage)
        assertFalse(DetailOmission.NODE_LIMIT in result.omissions)
        assertTrue(result.visitedNodes!! < 500)
        val formResult = sink.details.single { it.form != null }
        assertEquals(DetailCoverage.PARTIAL, formResult.coverage)
        assertTrue(DetailOmission.NODE_LIMIT in formResult.omissions)
        assertEquals(500, formResult.visitedNodes)
        assertTrue(result.extractionNanos!! <= formResult.extractionNanos!!)
        val text = Gson().toJson(sink.details)
        assertFalse(text.contains("PRIVATE_")); assertFalse(text.contains("987654321"))
        assertTrue(text.toByteArray().size <= 32768)
        assertNotNull(safeDetailCopy(result))
    }

    @Test fun historyRowsAndLargeRowSubtreesRemainBoundedAndIncompleteIsNeverCalledComplete() {
        val (_, many) = captureHistory("<table id='dataTable'><tbody>" + historyRow("").repeat(40) + "</tbody></table>")
        val manyResult = many.details.single { it.history != null }
        assertFalse(manyResult.history!!.allRowsScanned)
        assertTrue(manyResult.history.rowsExamined <= 32)
        assertTrue(manyResult.visitedNodes!! <= 500)
        assertTrue(manyResult.coverage == DetailCoverage.PARTIAL)
        val (_, rowLimit) = captureHistory("<table id='dataTable'><tbody>" + "<tr><td></td></tr>".repeat(40) + "</tbody></table>")
        assertTrue(DetailOmission.HISTORY_ROW_LIMIT in rowLimit.details.first().omissions)
        assertEquals(32, rowLimit.details.first().history!!.rowsExamined)
        val (_, huge) = captureHistory("<table id='dataTable'><tbody>" + historyRow("<i></i>".repeat(600)) + "</tbody></table>")
        val hugeResult = huge.details.single { it.history != null }
        assertFalse(hugeResult.history!!.allRowsScanned)
        assertTrue(DetailOmission.NODE_LIMIT in hugeResult.omissions)
        val (_, missing) = captureHistory("<form><input name='qty[]'></form>")
        assertFalse(missing.details.first().history!!.tablePresent)
        assertFalse(missing.details.first().history!!.allRowsScanned)
    }

    @Test fun historyUsesSameResponseAndRunTimeBudgetsAndObserverFailureCannotChangeParserResult() {
        var tick = 0L
        val extractor = SafeSiteExtractor { tick.also { tick += 1_000_000L } }
        val (_, sink) = captureHistory("<table id='dataTable'><tbody>" + historyRow("").repeat(20) + "</tbody></table>", extractor)
        assertTrue(sink.details.all { it.coverage == DetailCoverage.PARTIAL })
        assertTrue(sink.details.any { DetailOmission.TIME_LIMIT in it.omissions })
        val doc = Jsoup.parse("<table id='dataTable'><tbody>" + historyRow("<a href='javascript:jsOrderCxl(42)'>취소</a>") + "</tbody></table>")
        assertEquals(SiteParser.orders(doc), SiteParser.orders(doc) { _, _, _ -> throw IllegalStateException("PRIVATE_FAILURE") })
        val observed = Sink()
        val before = doc.outerHtml()
        val orders = SiteParser.orders(doc) { table, body, rows ->
            SafeSiteExtractor { 0L }.document(doc, base, observed, observed.capture, DiagnosticRoute.HISTORY, table, body, rows)
        }
        assertEquals(before, doc.outerHtml()); assertEquals(2, orders.single().quantity)
        assertEquals(1, observed.details.single { it.history != null }.history!!.rowsExamined)
    }
}
