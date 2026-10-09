package com.fullmetalsonic.dosirak.site

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Locale

/** Matches the observed standalone error notice, not an inferred firewall rule. */
internal object CloudbricClassifier {
    const val CODE = "SECURITY_BLOCK_CONTRACT"
    const val MESSAGE = "사이트 보안 장비가 요청을 차단했습니다. 재시도하지 말고 사이트 운영자에게 확인하세요."
    private val heading = Regex("^400 bad request(?:\\s|$)")
    private val whitespace = Regex("\\s+")
    private val notice = "Your request was blocked due to apparent client error such as malformed request syntax, invalid request message framing, or deceptive request routing."
        .lowercase(Locale.ROOT)

    fun isBlocked(html: String): Boolean = isBlocked(Jsoup.parse(html))

    fun isBlocked(document: Document): Boolean {
        if (document.select("#dataTable, #orderGo, #go-pay, input[name=uid], input[name=pwd]").isNotEmpty()) return false
        val visible = document.body()?.text().orEmpty().replace(whitespace, " ").trim().lowercase(Locale.ROOT)
        return heading.containsMatchIn(visible) && visible.contains(notice) &&
            visible.contains("cloudbric help center") && visible.contains("powered by cloudbric")
    }
}
