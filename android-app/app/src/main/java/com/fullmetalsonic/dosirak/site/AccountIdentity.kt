package com.fullmetalsonic.dosirak.site

internal fun matchesStoredAccount(observedId: String, expectedId: String): Boolean =
    expectedId.isNotBlank() && (observedId == expectedId ||
        expectedId.matches(Regex("[0-9]{6}")) && observedId == "PC$expectedId")
