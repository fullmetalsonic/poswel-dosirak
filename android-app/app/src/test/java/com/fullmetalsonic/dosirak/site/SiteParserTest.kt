package com.fullmetalsonic.dosirak.site

import org.junit.Assert.*
import org.junit.Test

class SiteParserTest {
    private val rawHeaders = "<thead><tr>" + listOf("수령일자", "메뉴명", "주문수량", "배송지", "배송상태", "결제금액", "주문취소", "평가하기")
        .joinToString("") { "<th>$it</th>" } + "</tr></thead>"
    private val rawCancelledRow = """<tr><td class='date'>2026-10-17</td><td class='menu'>점심</td><td class='qtt'></td>
        <td></td><td class='state'>취소</td><td class='pay'></td><td>26.10.17 06:14</td><td></td></tr>"""
    private fun rawHistory(headers: String = rawHeaders, row: String = rawCancelledRow) =
        SiteParser.document("<table id='dataTable'>$headers<tbody>$row</tbody></table>")
    private fun emptyHistory(headers: String = rawHeaders, body: String = "") =
        "<table id='dataTable'>$headers<tbody>$body</tbody></table>"
    private fun assertHistoryFailure(html: String, code: String = "HISTORY_CONTRACT") {
        val failure = assertThrows(SiteException::class.java) {
            SiteParser.orders(SiteParser.document(html, trackPosition = true))
        }
        assertEquals(code, failure.code)
        assertFalse(failure.submissionPossible)
    }

    @Test fun canonicalCompleteEmptyMonthHistoryIsValidWithoutPlaceholderRow() {
        assertTrue(SiteParser.orders(SiteParser.document(emptyHistory(), trackPosition = true)).isEmpty())
        val formatted = rawHeaders.replace("수령일자", " 수령\n일자 ")
            .replace("메뉴명", "메뉴&nbsp;명").replace("주문수량", "주문<br>수량")
            .replace("결제금액", " 결제\u3000금액 ")
        assertTrue(SiteParser.orders(SiteParser.document(
            emptyHistory(formatted, " \n <!-- no orders --> \t"), trackPosition = true)).isEmpty())
    }

    @Test fun emptyMonthWithTotalFooterDoesNotTreatTheSummaryAsAnOrder() {
        val html = javaClass.getResource("/site/history-empty.html")!!.readText()
        assertTrue(SiteParser.orders(SiteParser.document(html, trackPosition = true)).isEmpty())
    }

    @Test fun malformedFooterOrOrderRowsOutsideTheBodyCannotBecomeEmptyHistory() {
        val html = javaClass.getResource("/site/history-empty.html")!!.readText()
        listOf(
            html.replace("</tfoot>", ""),
            html.replace("</tfoot>", "</tfoot><tfoot><tr><th>Total</th></tr></tfoot>"),
            html.replace("<th colspan=\"2\">Total</th>", "<th class='date' colspan='2'>2026-11-05</th>"),
            html.replace("<th colspan=\"2\">Total</th>", "<th colspan='2'>2026-11-05</th>"),
            html.replace("</tfoot>", "<tbody></tbody></tfoot>"),
            html.replace("</tfoot>", "</tfoot><tr><td>unknown row</td></tr>")
        ).forEach { assertHistoryFailure(it) }
    }

    @Test fun untrackedEmptyTableCannotProveTheOriginalResponseWasComplete() {
        val failure = assertThrows(SiteException::class.java) { SiteParser.orders(SiteParser.document(emptyHistory())) }
        assertEquals("HISTORY_CONTRACT", failure.code)
    }

    @Test fun emptyHistoryRequiresUniqueActualTableBodyAndHeader() {
        listOf(
            "<html></html>",
            "<h1>500 Internal Server Error</h1>",
            "<table id='dataTable'>$rawHeaders</table>",
            "<table id='dataTable'><tbody></tbody></table>",
            "<div id='dataTable'>$rawHeaders<tbody></tbody></div>",
            emptyHistory() + emptyHistory(),
            emptyHistory().replace("</tbody>", "</tbody><tbody></tbody>"),
            emptyHistory().replace("</thead>", "</thead>$rawHeaders")
        ).forEach { assertHistoryFailure(it) }
    }

    @Test fun emptyHistoryRejectsUnknownDuplicateReorderedAndSpannedHeaders() {
        listOf(
            rawHeaders.replace("메뉴명", "메뉴"),
            rawHeaders.replace("평가하기", "주문취소"),
            rawHeaders.replace("<th>평가하기</th>", ""),
            rawHeaders.replace("</tr>", "<th>추가열</th></tr>"),
            rawHeaders.replace("<th>수령일자</th><th>메뉴명</th>", "<th>메뉴명</th><th>수령일자</th>"),
            rawHeaders.replace("<th>주문취소", "<th colspan='2'>주문취소"),
            rawHeaders.replace("<th>주문취소", "<th rowspan='2'>주문취소"),
            rawHeaders.replace("</thead>", "<tr><th>주문취소</th></tr></thead>")
        ).forEach { assertHistoryFailure(emptyHistory(it)) }
    }

    @Test fun emptyHistoryRejectsTruncatedAndParserRepairedTableMarkup() {
        val complete = emptyHistory()
        listOf(
            complete.substringBefore("</tbody>"),
            complete.substringBefore("</table>"),
            complete.replace("</tbody>", ""),
            complete.replace("</thead>", ""),
            complete.replace("</th>", ""),
            emptyHistory(body = "<div>오류</div>"),
            emptyHistory(body = "<input name='unknown'>"),
            complete.replace("<tbody>", "<div>오류</div><tbody>")
        ).forEach { assertHistoryFailure(it) }
    }

    @Test fun unknownOrErrorContentInHistoryBodyCannotBecomeEmptySuccess() {
        listOf("오류가 발생했습니다.", "<script>throw Error('failed')</script>",
            "<tr></tr>", "<tr><td>확인할 수 없는 응답</td></tr>",
            "<tr><td class='date'>잘못된 날짜</td></tr>")
            .forEach { assertHistoryFailure(emptyHistory(body = it)) }
    }

    @Test fun emptyCanonicalTableDoesNotBypassLoginOrAdditionalAuthentication() {
        assertHistoryFailure("<input name='uid'><input name='pwd'>" + emptyHistory(), "LOGIN_REQUIRED")
        for (control in listOf("<input name='captcha'>", "<input name='otp'>", "<iframe src='recaptcha'></iframe>")) {
            assertHistoryFailure(control + emptyHistory(), "ADDITIONAL_AUTH")
        }
        assertHistoryFailure(javaClass.getResource("/site/cloudbric-block.html")!!.readText(), CloudbricClassifier.CODE)
    }

    @Test fun explicitNoOrdersPlaceholderRemainsValidWithoutHeadersOrSourceTracking() {
        assertTrue(SiteParser.orders(rawHistory(headers = "",
            row = "<tr><td colspan='8'>주문 내역이 없습니다.</td></tr>")).isEmpty())
    }

    @Test fun rawHistoryWithoutDynamicCancellationClassUsesUniqueHeader() {
        val order = SiteParser.orders(rawHistory()).single()
        assertEquals("2026-10-17T06:14", order.cancelledAt)
        assertNull(order.quantity)
        assertNull(order.total)
        val spaced = rawHeaders.replace("주문취소", " 주문 \n 취소 ")
        assertEquals(order, SiteParser.orders(rawHistory(spaced)).single())
    }

    @Test fun ambiguousCancellationHeaderOrWidthOnlyRejectsTimestampNotOrdinaryHistory() {
        val malformed = listOf(
            rawHistory(rawHeaders.replace("주문취소", "취소 요청")),
            rawHistory(rawHeaders.replace("평가하기", "주문취소")),
            rawHistory(rawHeaders.replace("<th>주문취소", "<th colspan='2'>주문취소")),
            rawHistory(rawHeaders.replace("<th>주문취소", "<th rowspan='2'>주문취소")),
            rawHistory(row = rawCancelledRow.replace("<td></td></tr>", "</tr>")),
            rawHistory(row = rawCancelledRow.replace("</tr>", "<td></td></tr>")),
            rawHistory(rawHeaders.replace("</thead>", "<tr><th>주문취소</th></tr></thead>")),
            rawHistory(row = rawCancelledRow.replace("<td></td><td class='state'>", "<td class='btn-cancle'></td><td class='state'>"))
        )
        malformed.forEach { doc ->
            val order = SiteParser.orders(doc).single()
            assertEquals("취소", order.status)
            assertNull(order.cancelledAt)
        }
    }

    @Test fun cancelledHistoryTimestampIsStrictAndOptionalWithoutChangingNormalRows() {
        fun row(state: String, timestamp: String) = SiteParser.document("""<table id='dataTable'><tbody><tr>
            <td class='date'>2026-10-17</td><td class='menu'>점심</td><td class='qtt'></td>
            <td class='state'>$state</td><td class='pay'></td><td class='btn-cancle'>$timestamp</td>
            </tr></tbody></table>""")
        val cancelled = SiteParser.orders(row("취소", "26.10.17 06:14")).single()
        assertEquals("2026-10-17T06:14", cancelled.cancelledAt)
        assertNull(cancelled.quantity)
        assertNull(cancelled.total)
        assertNull(SiteParser.orders(row("취소", "26.02.30 06:14")).single().cancelledAt)
        assertNull(SiteParser.orders(row("취소요청", "26.10.17 06:14")).single().cancelledAt)
        assertNull(SiteParser.orders(row("취소", "")).single().cancelledAt)
    }
    @Test fun escapedMenuStringAndEmbeddedCommasRemainOneArgument() {
        val args = ScriptLiterals.arguments("goPay('741','한식','소스, 샐러드\\\' + \\uD55C\\x26','2026-10-09','5000','L',$(this));", "goPay")!!
        assertEquals(7, args.size); assertEquals("소스, 샐러드' + 한&", args[2])
    }
    @Test fun executableExpressionIsRejected() {
        assertNull(ScriptLiterals.arguments("goPay('741',attack(),'menu','2026-10-09','5000','L',$(this))", "goPay"))
        assertNull(ScriptLiterals.arguments("goPay('741','k','m','2026-10-09','5000','L',$(this));attack()", "goPay"))
    }
    @Test fun successfulControlsPreserveRepeatedFieldsAndCheckedState() {
        val doc = SiteParser.document("""<form><input name='a' value='1'><input name='a' value='2'><input name='ignored' disabled><input name='no' type='checkbox'><input name='yes' type='checkbox' checked><textarea name='text'>a&amp;+한글</textarea><select name='choice'><option value='a'>A</option><option value='b' selected>B</option></select></form>""")
        val fields = SiteParser.fields(doc.selectFirst("form")!!)
        assertEquals(listOf("1", "2"), fields["a"]); assertEquals(listOf("on"), fields["yes"])
        assertEquals(listOf("a&+한글"), fields["text"]); assertEquals(listOf("b"), fields["choice"])
        assertFalse(fields.containsKey("no")); assertFalse(fields.containsKey("ignored"))
    }
    @Test fun malformedMoneyCannotBeSilentlyParsed() {
        assertNull(SiteParser.amount("가격 없음")); assertNull(SiteParser.amount("50,00")); assertNull(SiteParser.amount("-5000"))
        assertEquals(5000L, SiteParser.amount("5,000 원"))
    }
}
