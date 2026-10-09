package com.fullmetalsonic.dosirak.site

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class LoginContractParserTest {
    private val base = "https://dosirak.poswel.co.kr/".toHttpUrl()
    private fun fixture() = javaClass.getResource("/site/login-dynamic.html")!!.readText()
    private fun rejected(html: String) {
        val failure = assertThrows(SiteException::class.java) { LoginContractParser.fields(SiteParser.document(html), base) }
        assertEquals("LOGIN_CONTRACT", failure.code); assertFalse(failure.submissionPossible)
    }

    @Test fun observedBlankFormAndInlineBindingUseFreshTokenWithoutAutoLogin() {
        val fields = LoginContractParser.fields(SiteParser.document(fixture()), base)
        assertEquals(listOf("mock-dynamic-token"), fields["utk"])
        assertEquals(listOf(""), fields["uid"]); assertEquals(listOf(""), fields["pwd"])
        assertFalse(fields.containsKey("isauto"))
    }

    @Test fun explicitDifferentActionMethodOrFormAndForeignHandlerActionAreRejected() {
        for (html in listOf(fixture().replace("action=\"\"", "action=\"https://example.org/login.check.php\""),
            fixture().replace("method=\"\"", "method=\"get\""), fixture().replace("id=\"loginform\"", "id=\"otherform\""),
            fixture().replace("https://dosirak.poswel.co.kr/login.check.php", "https://example.org/login.check.php"),
            fixture().replace("'#loginform'", "'#otherform'").replace("\"#loginform\"", "\"#otherform\""))) {
            rejected(html)
        }
    }

    @Test fun duplicateInputAndEmptyOrDisabledTokenAreRejected() {
        for (name in listOf("uid", "pwd", "utk")) {
            rejected(fixture().replace("</form>", "<input name='$name' value='duplicate'></form>"))
        }
        rejected(fixture().replace("value=\"mock-dynamic-token\"", "value=\"\""))
        rejected(fixture().replace("name=\"utk\"", "name=\"utk\" disabled"))
    }

    @Test fun changedBindingOrPollutedHandlerCannotAuthorizeBlankForm() {
        rejected(fixture().replace("setSubmit(); });", "setSubmit(); attack(); });"))
        rejected(fixture().replace(".submit();", ".submit(); attack();"))
        rejected(fixture().replace(".btn-login", ".other-button"))
        rejected(fixture().replace("href=\"#\"", "href=\"javascript:setSubmit();\""))
        rejected(fixture().replace("<script>", "<script src='unknown.js'>"))
        rejected(fixture().replace("</body>", "<script>function setSubmit(){}</script></body>"))
        rejected(fixture().replace("</body>", "<script>${'$'}('.btn-login').on('click', function(){ attack(); });</script></body>"))
    }

    @Test fun unrelatedInlineRegexpAndTemplateDoNotInvalidateObservedLoginContract() {
        val html = fixture().replace("</body>", """<script>const expression = /["']/g; const message = `Unrelated "help"`;</script></body>""")
        assertEquals(listOf("mock-dynamic-token"), LoginContractParser.fields(SiteParser.document(html), base)["utk"])
    }
}
