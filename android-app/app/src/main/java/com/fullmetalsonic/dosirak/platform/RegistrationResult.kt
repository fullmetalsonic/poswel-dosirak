package com.fullmetalsonic.dosirak.platform

// REGISTERED means AlarmManager accepted the alarm, not that an order succeeded.
enum class RegistrationCode { REGISTERED, STOPPED, NO_FUTURE_PLAN, BLOCKED, FAILED }

data class RegistrationResult(
    val code: RegistrationCode,
    val message: String,
    val preparationWarning: String? = null,
    val alarmEpochMillis: Long? = null
)
