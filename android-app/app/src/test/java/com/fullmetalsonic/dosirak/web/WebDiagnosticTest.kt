package com.fullmetalsonic.dosirak.web

import org.junit.Assert.*
import org.junit.Test

class WebDiagnosticTest {
    @Test fun queryCredentialsAndIdentifiersNeverAppearInDiagnostics() {
        val url = "https://dosirak.poswel.co.kr/order.list.php?uid=PRIVATE_ID&pwd=PRIVATE_PASSWORD#PRIVATE_TOKEN"
        val event = WebDiagnostic(100L, WebEvent.REQUEST, WebDiagnostic.route(url),
            WebDiagnostic.method("POST"), true, 403, "130.0.1")
        val line = event.safeLogLine()
        assertTrue(line.contains("route=HISTORY"))
        assertTrue(line.contains("method=POST"))
        listOf("PRIVATE_ID", "PRIVATE_PASSWORD", "PRIVATE_TOKEN", "uid=", "pwd=", "order.list.php").forEach {
            assertFalse(line.contains(it))
        }
    }

    @Test fun unknownPathAndMethodHaveOnlyFixedCategories() {
        assertEquals(WebRoute.OTHER_TRUSTED, WebDiagnostic.route("https://dosirak.poswel.co.kr/PRIVATE_ID"))
        assertEquals(WebRoute.EXTERNAL, WebDiagnostic.route("https://user:PRIVATE_PASSWORD@example.com/"))
        assertEquals(WebRoute.INVALID, WebDiagnostic.route("not a valid URI"))
        assertEquals(WebMethod.OTHER, WebDiagnostic.method("PRIVATE_SECRET"))
    }

    @Test fun providerAndUnknownFrameCannotInjectLogFields() {
        val event = WebDiagnostic(200L, WebEvent.SSL_ERROR, WebRoute.HOME, WebMethod.UNKNOWN,
            null, 3, "130.1\nPRIVATE_PASSWORD")
        assertTrue(event.safeLogLine().contains("mainFrame=unknown"))
        assertTrue(event.safeLogLine().contains("provider=unknown"))
        assertFalse(event.safeLogLine().contains("PRIVATE_PASSWORD"))
    }

    @Test fun nativeLoginFailureCodeIsRestrictedToKnownEnum() {
        assertEquals(LoginCheckCode.SECURITY_BLOCK_CONTRACT, LoginCheckCode.fromSiteCode("SECURITY_BLOCK_CONTRACT"))
        assertEquals(LoginCheckCode.LOGIN_REQUIRED, LoginCheckCode.fromSiteCode("LOGIN_REQUIRED"))
        assertEquals(LoginCheckCode.OTHER, LoginCheckCode.fromSiteCode("PRIVATE_PASSWORD"))
    }
}
