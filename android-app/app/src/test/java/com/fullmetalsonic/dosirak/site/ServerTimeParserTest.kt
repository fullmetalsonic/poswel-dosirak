package com.fullmetalsonic.dosirak.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ServerTimeParserTest {
    private fun expected(text: String): Long = LocalDateTime.parse(text.replace(' ', 'T'))
        .atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()

    @Test fun onlyUniquePlainInlineLiteralBecomesKstEpoch() {
        val html = "<script>function Timer(){ var servertimeinfo = '2026-10-09 06:00:00'; }</script>"
        assertEquals(expected("2026-10-09 06:00:00"), ServerTimeParser.parse(html))
    }

    @Test fun invalidDatesHoursAndLeapSecondsAreRejected() {
        for (value in listOf("2026-02-29 06:00:00", "2026-10-09 24:00:00", "2026-10-09 06:00:60",
            "2026-13-01 06:00:00", "2026-10-09T06:00:00", "2026-1-09 06:00:00")) {
            assertThrows(SiteException::class.java) { ServerTimeParser.parse("<script>var servertimeinfo='$value';</script>") }
        }
    }

    @Test fun missingDuplicatedComputedOrEscapedLiteralIsRejected() {
        for (script in listOf("var other='2026-10-09 06:00:00';",
            "var servertimeinfo='2026-10-09 06:00:00';var servertimeinfo='2026-10-09 06:00:00';",
            "var servertimeinfo='2026-10-09 '+ '06:00:00';",
            "var servertimeinfo=new Date();", "var servertimeinfo=`2026-10-09 06:00:00`;",
            "var servertimeinfo='2026-10-09 06:00:\\x30\\x30';")) {
            assertThrows(SiteException::class.java) { ServerTimeParser.parse("<script>$script</script>") }
        }
    }

    @Test fun commentsStringsRegexExternalScriptsAndHtmlAreNotDefinitions() {
        val real = "var servertimeinfo='2026-10-09 06:00:00';"
        val decoys = """// servertimeinfo='2025-01-01 00:00:00';
            /* var servertimeinfo='2025-01-01 00:00:00'; */
            var note="servertimeinfo='2025-01-01 00:00:00';";
            var pattern=/servertimeinfo='2025-01-01 00:00:00';/;
        """.trimIndent()
        assertEquals(expected("2026-10-09 06:00:00"), ServerTimeParser.parse("<p>$real</p><script src='/other.js'>$real</script><script>$decoys$real</script>"))
        assertThrows(SiteException::class.java) { ServerTimeParser.parse("<script>$decoys</script>") }
    }

    @Test fun objectPropertyOrUnterminatedStatementCannotSupplyClock() {
        for (script in listOf("window.servertimeinfo='2026-10-09 06:00:00';", "var servertimeinfo='2026-10-09 06:00:00'")) {
            assertThrows(SiteException::class.java) { ServerTimeParser.parse("<script>$script</script>") }
        }
    }
}
