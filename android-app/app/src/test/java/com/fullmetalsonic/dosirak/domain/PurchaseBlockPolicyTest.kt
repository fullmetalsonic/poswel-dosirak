package com.fullmetalsonic.dosirak.domain

import org.junit.Assert.*
import org.junit.Test

class PurchaseBlockPolicyTest {
    @Test fun onlyCriticalAccountAmountAuthenticationAndSecurityErrorsBlockFuturePurchases() {
        listOf("ACCOUNT_MISMATCH", "ACCOUNT_UNVERIFIED", "ADDITIONAL_AUTH", "PRICE_MISSING",
            "PRICE_INVALID", "PRICE_MISMATCH", "SECURITY_BLOCK_CONTRACT", "REDIRECT_BLOCKED").forEach {
            assertTrue(it, PurchaseBlockPolicy.blocksFuturePurchases(it))
        }
        listOf("HISTORY_CONTRACT", "HISTORY_CONFLICT", "LOGIN_REQUIRED", "LOGIN_FAILED", "LOGIN_CONTRACT",
            "SESSION_CONTRACT", "MAIN_CONTRACT", "MENU_CONTRACT", "CART_CONTRACT", "CART_CONFLICT",
            "CHECKOUT_CONTRACT", "CHECKOUT_CONFLICT", "JSON_CONTRACT", "NETWORK", "NEW_CONTRACT").forEach {
            assertFalse(it, PurchaseBlockPolicy.blocksFuturePurchases(it))
        }
    }

    @Test fun legacyAttemptOnlyBlocksRecoverOnceWithoutChangingAuthorizationOrAccount() {
        listOf("HISTORY_CONTRACT", "HISTORY_CONFLICT", "LOGIN_REQUIRED", "LOGIN_FAILED", "LOGIN_CONTRACT",
            "SESSION_CONTRACT", "MAIN_CONTRACT", "MENU_CONTRACT", "CART_CONTRACT", "CART_CONFLICT",
            "CHECKOUT_CONTRACT", "CHECKOUT_CONFLICT", "JSON_CONTRACT").forEach { code ->
            listOf(false, true).forEach { enabled ->
                val settings = AppSettings(masterEnabled = enabled, liveScope = LiveScope.RECURRING,
                    displayPriceRiskAccepted = true, accountGeneration = 4, verifiedLiveAccountGeneration = 4,
                    generation = 8, liveBlockedReason = "$code: legacy block")
                val recovered = PurchaseBlockPolicy.recoverLegacyBlock(settings)
                assertEquals(settings.copy(liveBlockedReason = null, generation = 9), recovered)
                assertSame(recovered, PurchaseBlockPolicy.recoverLegacyBlock(recovered))
            }
        }
    }

    @Test fun criticalUnknownAndUncodedBlocksNeverRecoverAutomatically() {
        listOf(null, "ACCOUNT_MISMATCH: test", "ACCOUNT_UNVERIFIED: test", "ADDITIONAL_AUTH: test",
            "PRICE_MISSING: test", "PRICE_INVALID: test", "PRICE_MISMATCH: test", "SECURITY_BLOCK_CONTRACT: test",
            "REDIRECT_BLOCKED: test", "NEW_CONTRACT: test", "표시금액과 실제 주문금액이 다릅니다.",
            "HISTORY_CONTRACT", "HISTORY_CONTRACT_CHANGED: test").forEach { reason ->
            val settings = AppSettings(liveBlockedReason = reason, generation = 8)
            assertSame(settings, PurchaseBlockPolicy.recoverLegacyBlock(settings))
        }
    }

    @Test fun rejectedCredentialsOrLoginFormStopCurrentAttemptWithoutDisablingFuturePurchases() {
        for (code in listOf("LOGIN_FAILED", "LOGIN_CONTRACT")) {
            assertFalse(PurchaseBlockPolicy.canRetryAttempt(code))
            assertFalse(PurchaseBlockPolicy.blocksFuturePurchases(code))
        }
        listOf(null, "NETWORK", "HISTORY_CONTRACT", "SESSION_CONTRACT").forEach {
            assertTrue(PurchaseBlockPolicy.canRetryAttempt(it))
        }
    }
}
