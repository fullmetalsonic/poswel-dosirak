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
}
