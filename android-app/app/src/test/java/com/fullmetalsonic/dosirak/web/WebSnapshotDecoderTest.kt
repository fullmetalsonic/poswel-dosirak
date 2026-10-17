package com.fullmetalsonic.dosirak.web

import com.fullmetalsonic.dosirak.diagnostics.*
import org.junit.Assert.*
import org.junit.Test

class WebSnapshotDecoderTest {
    private val token = "00000000-0000-0000-0000-000000000001"
    private val row = """{"date":"2026-10-18","quantity":0,"total":null,"state":"CANCELLED","cancelCell":true,"cancelTime":true,"cancelLink":false,"handler":false,"rating":false}"""
    private fun history(rows: String = row, all: Boolean = true) = """{"v":1,"token":"$token","seq":1,"type":"HISTORY","snapshot":{"table":true,"body":true,"all":$all,"rows":[$rows]}}"""
    @Test fun cancelledHistoryHasOnlyBusinessFactsAndCurrentDocumentScope() {
        val message = WebCaptureDecoder.decode(history())!!
        val observation = WebCaptureObservation.fromMessage(message)!!
        assertEquals(SafeHistoryStatus.CANCELLED, observation.history!!.rows.single().status)
        assertEquals(DetailHistoryScope.CURRENT_DOCUMENT_ONLY, observation.historyScope)
        assertTrue(observation.history!!.allRowsScanned)
        assertNull(observation.completionAlertMatched)
        assertNull(observation.httpStatus)
    }
    @Test fun largeAndMalformedPrivateHistoryIsRejectedRatherThanLogged() {
        assertNotNull(WebCaptureDecoder.decode(history(List(32) { row }.joinToString(","), false)))
        assertNull(WebCaptureDecoder.decode(history(List(33) { row }.joinToString(","), false)))
        assertNull(WebCaptureDecoder.decode(history(row.replace("\"date\":", "\"address\":\"PRIVATE_ADDRESS\",\"date\":"))))
        assertNull(WebCaptureDecoder.decode(history(row.replace("\"quantity\":0", "\"quantity\":0,\"quantity\":1"))))
        assertNull(WebCaptureDecoder.decode(history(row.replace("CANCELLED", "PRIVATE_SECRET"))))
        assertNull(WebCaptureDecoder.decode(history(row.replace("2026-10-18", "2026-99-18"))))
    }
    @Test fun partialCheckoutDoesNotInferDateAndIsNotACompletion() {
        val raw = """{"v":1,"token":"$token","seq":1,"type":"CHECKOUT","snapshot":{"date":null,"quantity":1,"total":5000}}"""
        val detail = WebCaptureObservation.fromMessage(WebCaptureDecoder.decode(raw)!!)!!
        assertEquals(DetailCoverage.PARTIAL, detail.coverage)
        assertEquals(1, detail.business!!.quantity)
        assertEquals(5000L, detail.business!!.total)
        assertNull(detail.business!!.targetDate)
        assertNull(detail.httpStatus)
    }
    @Test fun snapshotsStillRequireDocumentHandshakeAndCurrentToken() {
        val gate = WebCaptureGate(); val page = "https://dosirak.poswel.co.kr/order.list.php"
        gate.activate(token, page)
        fun accept(raw: String, enabled: Boolean = true) = gate.accept(raw, "https://dosirak.poswel.co.kr", true, page, enabled, true)
        assertNull(accept(history()))
        accept("""{"v":1,"token":"$token","seq":0,"type":"READY"}""")
        assertNotNull(accept(history()))
        assertNull(accept(history().replace("\"seq\":1", "\"seq\":2"), false))
    }
}
