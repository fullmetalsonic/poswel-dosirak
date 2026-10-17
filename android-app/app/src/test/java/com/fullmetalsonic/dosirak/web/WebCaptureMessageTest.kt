package com.fullmetalsonic.dosirak.web

import org.junit.Assert.*
import org.junit.Test
import com.fullmetalsonic.dosirak.diagnostics.*

class WebCaptureMessageTest {
    private val token = "00000000-0000-0000-0000-000000000001"
    private val page = "https://dosirak.poswel.co.kr/order.list.php"
    private fun ready() = """{"v":1,"token":"$token","seq":0,"type":"READY"}"""
    private fun event(seq: Int = 1) = """{"v":1,"token":"$token","seq":$seq,"type":"XHR","route":"CANCEL","method":"POST","status":200,"duration":5,"success":true}"""
    private fun gate() = WebCaptureGate().apply { activate(token, page) }
    private fun WebCaptureGate.accept(text: String, origin: String = "https://dosirak.poswel.co.kr", main: Boolean = true,
        currentPage: String = page, enabled: Boolean = true, current: Boolean = true) =
        accept(text, origin, main, currentPage, enabled, current)

    @Test fun knownCancellationHasOnlyTypedSafeFacts() {
        val decoded = WebCaptureDecoder.decode(event())!!
        assertEquals(WebCaptureMessage.Route.CANCEL, decoded.route)
        assertEquals(true, decoded.success)
        assertEquals(200, decoded.status)
        assertEquals(5L, decoded.durationMillis)
    }

    @Test fun unknownPrivateKeysDuplicateAndNestedValuesAreRejected() {
        listOf(
            event().replace("\"v\":1", "\"v\":1,\"password\":\"PRIVATE_SECRET\""),
            event().replace("\"v\":1", "\"v\":1,\"v\":1"),
            event().replace("\"POST\"", "{\"token\":\"PRIVATE_SECRET\"}"),
            event().replace("true", "\"PRIVATE_SECRET\""),
            event().replace("\"CANCEL\"", "\"PRIVATE_ADDRESS\""),
            event() + "{}", "/*private*/" + event(), event().repeat(6)
        ).forEach { assertNull(WebCaptureDecoder.decode(it)) }
    }

    @Test fun numericCoercionsAndOutOfRangeValuesAreRejected() {
        listOf("-1", "257", "1.0", "1e1", "\"1\"", "true", "null", "[]").forEach {
            assertNull(WebCaptureDecoder.decode(event().replace("\"seq\":1", "\"seq\":$it")))
        }
        assertNull(WebCaptureDecoder.decode(event().replace("200", "600")))
        assertNull(WebCaptureDecoder.decode(event().replace("200", "99")))
        assertNull(WebCaptureDecoder.decode(event().replace("\"duration\":5", "\"duration\":600001")))
    }

    @Test fun originMustBeExactHttpsWithoutUserInfoOrUnapprovedPort() {
        assertTrue(WebCaptureDecoder.trustedOrigin("https://dosirak.poswel.co.kr"))
        assertTrue(WebCaptureDecoder.trustedOrigin("https://dosirak.poswel.co.kr:443"))
        listOf("http://dosirak.poswel.co.kr", "https://dosirak.poswel.co.kr:444", "https://dosirak.poswel.co.kr.evil.test",
            "https://private@dosirak.poswel.co.kr", "https://dosirak.poswel.co.kr/?token=private", "null").forEach {
            assertFalse(WebCaptureDecoder.trustedOrigin(it))
        }
    }

    @Test fun readyHandshakeFrameAndNavigationMustMatch() {
        val gate = gate()
        assertNull(gate.accept(event()))
        assertNull(gate.accept(ready(), main = false))
        assertNull(gate.accept(event()))
        assertNull(gate.accept(ready(), origin = "https://evil.test"))
        assertNull(gate.accept(event()))
        gate.accept(ready())
        assertNull(gate.accept(event(), currentPage = "https://dosirak.poswel.co.kr/"))
        assertNotNull(gate.accept(event()))
        assertNull(gate.accept(event()))
        assertNotNull(gate.accept(event(2)))
    }

    @Test fun offAndClearInvalidateAlreadyQueuedMessagesUntilFreshHandshake() {
        for (off in listOf(true, false)) {
            val gate = gate()
            gate.accept(ready())
            assertNull(gate.accept(event(), enabled = !off, current = off))
            assertNull(gate.accept(event()))
            gate.activate(token, page)
            assertNull(gate.accept(event()))
            gate.accept(ready())
            assertNotNull(gate.accept(event()))
        }
    }

    @Test fun oldTokenReorderedAndQuotaExceededEventsAreDropped() {
        val gate = gate()
        gate.accept(ready())
        assertNotNull(gate.accept(event(3)))
        assertNull(gate.accept(event(2)))
        assertNull(gate.accept(event(4).replace(token, "00000000-0000-0000-0000-000000000002")))
        assertNotNull(gate.accept(event(256)))
        assertNull(gate.accept(event(257)))
        gate.invalidate()
        assertNull(gate.accept(event(256)))
    }

    @Test fun formObservationCannotContainSuccessOrIdentifiers() {
        val form = event().replace("\"XHR\"", "\"FORM\"").replace("\"CANCEL\"", "\"SUBMIT\"")
            .replace("\"status\":200", "\"status\":0").replace(",\"success\":true", "")
        assertNotNull(WebCaptureDecoder.decode(form))
        val observation = WebCaptureObservation.fromMessage(WebCaptureDecoder.decode(form)!!)!!
        assertEquals(DetailKind.FORM_INTENT, observation.kind)
        assertEquals(DetailSource.WEB_OBSERVED, observation.source)
        assertEquals(DiagnosticResult.STARTED, observation.result)
        assertEquals(DiagnosticRoute.SUBMISSION, observation.route)
        assertNull(observation.httpStatus)
        assertNull(observation.submissionPossible)
        assertNull(observation.completionAlertMatched)
        assertNull(WebCaptureDecoder.decode(form.dropLast(1) + ",\"success\":true}"))
    }
}
