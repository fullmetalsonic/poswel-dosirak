package com.fullmetalsonic.dosirak.domain

object LoginRecoveryPolicy {
    fun canRecover(reason: String?, accountVerified: Boolean, expectedGeneration: Long, currentGeneration: Long): Boolean =
        accountVerified && expectedGeneration == currentGeneration && reason != null &&
            listOf("LOGIN_REQUIRED:", "LOGIN_FAILED:", "ACCOUNT_MISMATCH:", "LOGIN_CONTRACT:")
                .any(reason::startsWith)
}
