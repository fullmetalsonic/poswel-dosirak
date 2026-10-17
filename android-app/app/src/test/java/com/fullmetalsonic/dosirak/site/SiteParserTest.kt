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
