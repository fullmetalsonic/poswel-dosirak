package com.fullmetalsonic.dosirak.domain

import org.junit.Assert.*
import org.junit.Test

class LoginRecoveryPolicyTest {
    @Test fun verifiedSameAccountRecoversOnlyLoginFailures() {
        listOf("LOGIN_REQUIRED", "LOGIN_FAILED", "ACCOUNT_MISMATCH", "LOGIN_CONTRACT").forEach {
            assertTrue(LoginRecoveryPolicy.canRecover("$it: test", true, 6, 6))
        }
        listOf("SECURITY_BLOCK_CONTRACT", "SESSION_CONTRACT", "CHECKOUT_CONTRACT", "HISTORY_CONFLICT").forEach {
            assertFalse(LoginRecoveryPolicy.canRecover("$it: test", true, 6, 6))
        }
        assertFalse(LoginRecoveryPolicy.canRecover(null, true, 6, 6))
    }

    @Test fun failedVerificationOrChangedAccountKeepsLoginBlock() {
        assertFalse(LoginRecoveryPolicy.canRecover("LOGIN_CONTRACT: test", false, 6, 6))
        assertFalse(LoginRecoveryPolicy.canRecover("LOGIN_CONTRACT: test", true, 6, 7))
    }
}
