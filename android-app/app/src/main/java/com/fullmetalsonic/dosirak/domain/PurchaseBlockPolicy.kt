package com.fullmetalsonic.dosirak.domain

object PurchaseBlockPolicy {
    private val persistentCodes = setOf(
        "ACCOUNT_MISMATCH", "ACCOUNT_UNVERIFIED", "ADDITIONAL_AUTH",
        "PRICE_MISSING", "PRICE_INVALID", "PRICE_MISMATCH",
        "SECURITY_BLOCK_CONTRACT", "REDIRECT_BLOCKED"
    )
    private val legacyAttemptOnlyCodes = setOf(
        "HISTORY_CONTRACT", "HISTORY_CONFLICT", "SESSION_CONTRACT", "LOGIN_CONTRACT",
        "LOGIN_REQUIRED", "LOGIN_FAILED", "MAIN_CONTRACT", "MENU_CONTRACT",
        "CART_CONTRACT", "CART_CONFLICT", "CHECKOUT_CONTRACT", "CHECKOUT_CONFLICT", "JSON_CONTRACT"
    )

    fun blocksFuturePurchases(code: String): Boolean = code in persistentCodes

    fun canRetryAttempt(code: String?): Boolean = code != "LOGIN_FAILED" && code != "LOGIN_CONTRACT"

    fun recoverLegacyBlock(settings: AppSettings): AppSettings {
        val reason = settings.liveBlockedReason ?: return settings
        if (legacyAttemptOnlyCodes.none { reason.startsWith("$it:") }) return settings
        // Only retire the obsolete global gate; every purchase still validates the site and durable ledger.
        return settings.copy(liveBlockedReason = null, generation = settings.generation + 1)
    }
}
