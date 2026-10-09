package com.fullmetalsonic.dosirak.update

import org.junit.Assert.*
import org.junit.Test

class SemanticVersionTest {
    private fun version(value: String) = requireNotNull(SemanticVersion.parse(value))

    @Test fun comparesSameNewAndOlderVersionsNumerically() {
        assertEquals(0, version("0.1.6").compareTo(version("v0.1.6")))
        assertTrue(version("0.1.10") > version("0.1.9"))
        assertTrue(version("1.0.0") > version("0.99.99"))
        assertTrue(version("0.1.5") < version("0.1.6"))
    }

    @Test fun stableVersionIsNewerThanSameTestVersion() {
        assertTrue(version("0.1.6") > version("0.1.6-test"))
        assertTrue(version("0.1.6-test") > version("0.1.5"))
    }

    @Test fun prereleaseIdentifiersFollowNumericAndLexicalPrecedence() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        ordered.zipWithNext().forEach { (before, after) -> assertTrue(version(before) < version(after)) }
    }

    @Test fun buildMetadataDoesNotAffectPrecedence() {
        assertEquals(0, version("0.1.6+build.7").compareTo(version("0.1.6+build.8")))
    }

    @Test fun hugeNumericVersionsDoNotOverflow() {
        assertTrue(version("999999999999999999999999.1.0") > version("2.1.0"))
    }

    @Test fun malformedVersionsAndNumericLeadingZerosAreRejected() {
        listOf("", "latest", "0.1", "0.1.6.1", "01.1.6", "0.1.6-01", "0.1.6-", "0.1.6-rc..1",
            "0.1.6+", " 0.1.6", "0.1.6\n", "release-0.1.6").forEach { assertNull(it, SemanticVersion.parse(it)) }
    }
}
