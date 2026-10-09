package com.fullmetalsonic.dosirak.site

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class CloudbricClassifierTest {
    private fun fixture() = javaClass.getResource("/site/cloudbric-block.html")!!.readText()

    @Test fun observedVisibleNoticeIsRecognizedWithoutAssumingHeadingMarkup() {
        assertTrue(CloudbricClassifier.isBlocked(fixture()))
        assertTrue(CloudbricClassifier.isBlocked(fixture().replace("<div class=\"heading\">", "<h1>")
            .replace("</span> Bad Request</div>", "</span> Bad Request</h1>")))
    }

    @Test fun caseAndVisibleWhitespaceDoNotChangeClassification() {
        assertTrue(CloudbricClassifier.isBlocked(fixture().uppercase(Locale.ROOT).replace("CLIENT ERROR", "CLIENT\n ERROR")))
    }

    @Test fun genericBadRequestIsNotCloudbricEvidence() {
        assertFalse(CloudbricClassifier.isBlocked("<html><body><div>400 Bad Request</div><p>Invalid request.</p></body></html>"))
    }

    @Test fun normalArticleContainingAllPhrasesIsNotAStandaloneBlockNotice() {
        val article = fixture().replace("<body>", "<body><h1>Security troubleshooting guide</h1>")
        assertFalse(CloudbricClassifier.isBlocked(article))
    }

    @Test fun realSiteLoginOrOrderControlsPreventNoticeTextFalsePositive() {
        for (controls in listOf("<input name='uid'><input name='pwd'>", "<table id='dataTable'></table>", "<form id='orderGo'></form>")) {
            assertFalse(CloudbricClassifier.isBlocked(fixture().replace("</body>", "$controls</body>")))
        }
    }

    @Test fun missingBrandingOrDifferentMessageIsNotTheObservedContract() {
        for (html in listOf(fixture().replace("Powered by Cloudbric", ""), fixture().replace("Cloudbric Help Center", ""),
            fixture().replace("deceptive request routing", "an unknown reason"))) {
            assertFalse(CloudbricClassifier.isBlocked(html))
        }
    }

    @Test fun parserChallengeAndHistoryUseTypedBlockWithoutBodyDetails() {
        val document = SiteParser.document(fixture())
        for (operation in listOf<() -> Unit>({ SiteParser.checkChallenge(document) }, { SiteParser.orders(document) })) {
            val error = assertThrows(SiteException::class.java) { operation() }
            assertEquals(CloudbricClassifier.CODE, error.code)
            assertFalse(error.message.contains("malformed")); assertFalse(error.message.contains("09:29"))
            assertFalse(error.submissionPossible)
        }
    }
}
