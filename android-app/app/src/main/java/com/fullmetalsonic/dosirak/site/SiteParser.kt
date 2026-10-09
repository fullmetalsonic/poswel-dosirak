package com.fullmetalsonic.dosirak.site

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate

internal object SiteParser {
    fun document(html: String): Document = Jsoup.parse(html)

    fun isLogin(doc: Document): Boolean = doc.select("input[name=uid]:not([readonly])").isNotEmpty() &&
        doc.select("input[name=pwd]").isNotEmpty()

    fun isAuthenticated(doc: Document): Boolean = !isLogin(doc) &&
        (doc.select("a[href*=logout]").isNotEmpty() || doc.getElementById("dataTable") != null ||
            doc.getElementById("orderGo") != null)

    fun checkChallenge(doc: Document) {
        if (CloudbricClassifier.isBlocked(doc)) fail(CloudbricClassifier.CODE, CloudbricClassifier.MESSAGE)
        if (doc.select("[id*=captcha], [name*=captcha], iframe[src*=recaptcha], [name=otp], [name=verification_code]").isNotEmpty()) {
            fail("ADDITIONAL_AUTH", "사이트에서 추가 인증이 필요합니다.")
        }
    }

    fun orders(doc: Document): List<SiteOrder> {
        if (CloudbricClassifier.isBlocked(doc)) fail(CloudbricClassifier.CODE, CloudbricClassifier.MESSAGE)
        if (isLogin(doc)) fail("LOGIN_REQUIRED", "사이트 로그인이 필요합니다.")
        val table = doc.getElementById("dataTable") ?: fail("HISTORY_CONTRACT", "주문내역 구조를 확인할 수 없습니다.")
        val body = table.selectFirst("tbody") ?: fail("HISTORY_CONTRACT", "주문내역 본문이 없습니다.")
        if (body.select("tr").isEmpty()) fail("HISTORY_CONTRACT", "주문내역의 빈 결과를 확인할 수 없습니다.")
        return body.select("tr").mapNotNull { row ->
            val dateText = row.selectFirst(".date")?.text()
            if (dateText == null) {
                if (row.select("td").size == 1 && row.text().matches(Regex(".*(없습니다|없음|No data available|No matching records).*", RegexOption.IGNORE_CASE))) null
                else fail("HISTORY_CONTRACT", "주문내역 행의 필수 항목이 누락되었습니다.")
            } else {
                val date = date(dateText) ?: fail("HISTORY_CONTRACT", "주문내역 날짜를 확인할 수 없습니다.")
                val menu = requiredText(row, ".menu")
                val state = requiredText(row, ".state")
                val qtyElement = row.selectFirst(".qtt") ?: fail("HISTORY_CONTRACT", "주문내역 수량 항목이 없습니다.")
                val payElement = row.selectFirst(".pay") ?: fail("HISTORY_CONTRACT", "주문내역 금액 항목이 없습니다.")
                val qtyText = qtyElement.text().trim()
                val quantity = if (qtyText.isEmpty()) null else qtyText.toIntOrNull()?.takeIf { it > 0 }
                    ?: fail("HISTORY_CONTRACT", "주문내역 수량을 확인할 수 없습니다.")
                val payText = payElement.text().trim()
                val total = if (payText.isEmpty()) null else amount(payText)
                    ?: fail("HISTORY_CONTRACT", "주문내역 금액을 확인할 수 없습니다.")
                val ids = row.select("a[onclick], a[href]").flatMap { link ->
                    Regex("\\bjsOrderCxl\\(\\s*(['\"]?)([0-9]+)\\1\\s*\\)").findAll(link.attr("onclick") + " " + link.attr("href")).map { it.groupValues[2] }.toList()
                }.distinct()
                if (ids.size > 1) fail("HISTORY_CONTRACT", "주문내역 식별자가 서로 다릅니다.")
                SiteOrder(ids.singleOrNull(), date, quantity, state, total, menu)
            }
        }
    }

    fun checkout(doc: Document, date: LocalDate, quantity: Int, temporaryId: String): CheckoutSnapshot {
        if (isLogin(doc)) fail("LOGIN_REQUIRED", "사이트 로그인이 만료되었습니다.")
        checkChallenge(doc)
        val form = doc.getElementById("orderGo") ?: fail("CHECKOUT_CONTRACT", "신청정보 폼을 확인할 수 없습니다.")
        if (quantity !in 1..5) fail("QUANTITY", "수량은 1~5개여야 합니다.")
        val uid = form.select("input[name='uid[]']")
        val qty = form.select("input[name='qty[]']")
        if (uid.size != 1 || qty.size != 1 || uid[0].attr("value") != temporaryId ||
            qty[0].id() != "qty$temporaryId" || qty[0].attr("value").toIntOrNull() != quantity) {
            fail("CHECKOUT_CONFLICT", "임시 주문 항목과 신청정보가 일치하지 않습니다.")
        }
        val unit = amount(form.getElementById("priceEach$temporaryId")?.attr("value") ?: "")
            ?.takeIf { it > 0 } ?: fail("PRICE_MISSING", "개당 금액이 없습니다.")
        if (unit > Long.MAX_VALUE / quantity) fail("PRICE_INVALID", "금액 범위를 확인할 수 없습니다.")
        val expected = unit * quantity
        val totals = listOf("price$temporaryId", "orderAmt", "totamt").map { id ->
            amount(form.getElementById(id)?.attr("value") ?: "") ?: fail("PRICE_MISSING", "주문 총액이 없습니다.")
        }
        if (totals.any { it != expected }) fail("PRICE_MISMATCH", "수량과 주문 금액이 일치하지 않습니다.")
        val senderName = value(form, "od_name")
        val phone = value(form, "od_hp")
        val account = value(form, "od_jikbun")
        listOf("course", "delivery_nm", "delivery_det").forEach { value(form, it) }
        if (!validPhone(phone)) fail("PHONE_INVALID", "등록된 전화번호를 확인해 주세요.")
        if (form.getElementById("sameP")?.attr("type") != "checkbox") fail("CHECKOUT_CONTRACT", "수령인 정보 연결을 확인할 수 없습니다.")
        form.getElementById("rv_name")?.attr("value", senderName) ?: fail("CHECKOUT_CONTRACT", "수령인 이름 항목이 없습니다.")
        form.getElementById("rv_phone")?.attr("value", phone) ?: fail("CHECKOUT_CONTRACT", "수령인 전화 항목이 없습니다.")
        val fields = fields(form)
        listOf("od_jikbun", "od_name", "od_hp", "course", "delivery_nm", "delivery_det", "rv_name", "rv_phone").forEach { name ->
            if (fields[name]?.singleOrNull().isNullOrBlank()) fail("CHECKOUT_CONTRACT", "신청정보의 필수 전송 항목이 누락되었습니다.")
        }
        if (fields["payroll"] != listOf("on")) fail("PAYMENT_UNSUPPORTED", "급여공제 결제 선택을 확인해 주세요.")
        if (fields["uid[]"] != listOf(temporaryId) || fields["qty[]"] != listOf(quantity.toString())) fail("CHECKOUT_CONFLICT", "신청정보 수량 연결을 확인할 수 없습니다.")
        return CheckoutSnapshot(date, quantity, unit, expected, account, temporaryId, fields.mapValues { it.value.toList() })
    }

    fun fields(form: Element): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        form.select("input[name], select[name], textarea[name]").forEach { control ->
            val name = control.attr("name")
            if (name.isEmpty() || control.hasAttr("disabled") || control.parents().any { it.tagName() == "fieldset" && it.hasAttr("disabled") }) return@forEach
            val type = control.attr("type").lowercase()
            if (type in setOf("submit", "button", "reset", "image", "file")) return@forEach
            if (type in setOf("checkbox", "radio") && !control.hasAttr("checked")) return@forEach
            val values = when (control.tagName()) {
                "textarea" -> listOf(control.wholeText())
                "select" -> {
                    val options = control.select("option").filter { !it.hasAttr("disabled") && !it.parents().any { parent -> parent.tagName() == "optgroup" && parent.hasAttr("disabled") } }
                    val selected = options.filter { it.hasAttr("selected") }.let { if (it.isEmpty() && !control.hasAttr("multiple")) options.take(1) else it }
                    selected.map { if (it.hasAttr("value")) it.attr("value") else it.text() }
                }
                else -> listOf(if (type in setOf("checkbox", "radio") && !control.hasAttr("value")) "on" else control.attr("value"))
            }
            result.getOrPut(name) { mutableListOf() }.addAll(values)
        }
        return result
    }

    fun selectedValue(control: Element?): String? = when (control?.tagName()) {
        "select" -> control.selectFirst("option[selected]")?.let { if (it.hasAttr("value")) it.attr("value") else it.text() }
            ?: control.selectFirst("option")?.let { if (it.hasAttr("value")) it.attr("value") else it.text() }
        "input" -> control.attr("value")
        else -> null
    }?.takeIf { it.isNotBlank() }

    fun date(text: String): LocalDate? {
        val match = Regex("(?<![0-9])([0-9]{4})[-./년\\s]+([0-9]{1,2})[-./월\\s]+([0-9]{1,2})").find(text) ?: return null
        return runCatching { LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()) }.getOrNull()
    }

    fun amount(text: String): Long? = text.trim().takeIf { it.matches(Regex("[0-9]+(?:,[0-9]{3})*(?:\\s*원)?")) }
        ?.removeSuffix("원")?.trim()?.replace(",", "")?.toLongOrNull()

    private fun value(form: Element, id: String): String = form.getElementById(id)?.attr("value")?.trim()?.takeIf { it.isNotEmpty() }
        ?: fail("PROFILE_MISSING", "사이트의 주문자·배송 정보를 먼저 등록해 주세요.")

    private fun validPhone(value: String): Boolean = value.matches(Regex("[0-9()+ -]+")) && value.filter(Char::isDigit).length in 9..11
    private fun requiredText(row: Element, selector: String): String = row.selectFirst(selector)?.text()?.takeIf { it.isNotBlank() }
        ?: fail("HISTORY_CONTRACT", "주문내역 필수 항목이 없습니다.")

    fun fail(code: String, message: String): Nothing = throw SiteException(code, message)
}
