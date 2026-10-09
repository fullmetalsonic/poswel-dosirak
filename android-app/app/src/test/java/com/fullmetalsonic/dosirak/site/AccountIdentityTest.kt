package com.fullmetalsonic.dosirak.site

import org.junit.Assert.*
import org.junit.Test

class AccountIdentityTest {
    @Test fun exactAndFixedPcAsciiSixDigitIdentitiesMatchWithoutRewriting() {
        listOf("account" to "account", "123456" to "123456", "PC123456" to "123456",
            "PC001234" to "001234").forEach { (observed, expected) ->
            assertTrue(matchesStoredAccount(observed, expected))
        }
    }

    @Test fun otherAccountsPrefixesCaseLengthsWhitespaceAndNonAsciiMappingsDoNotMatch() {
        val nonAscii = "\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16"
        listOf("PC654321" to "123456", "pc123456" to "123456", "XX123456" to "123456",
            "PC123456x" to "123456", "PC 123456" to "123456", "PC12345" to "12345",
            "PC1234567" to "1234567", "PC$nonAscii" to nonAscii, "PC12A456" to "12A456",
            "PC1234" to "001234", "PCPC123456" to "123456", "PC123456 " to "123456",
            "" to "", " " to " ").forEach { (observed, expected) ->
            assertFalse(matchesStoredAccount(observed, expected))
        }
    }
}
