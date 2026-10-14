package com.fullmetalsonic.dosirak.diagnostics

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.io.File
import java.util.UUID

enum class DiagnosticEventType {
    RUN_STARTED, RUN_FINISHED, PLAN_CHECKED, ACCOUNT_CHECKED, SESSION_CHECKED, HISTORY_CHECKED,
    MENU_LOADED, CHECKOUT_STARTED, CHECKOUT_FINISHED, SUBMIT_STARTED, SUBMIT_FINISHED,
    LEDGER_WRITTEN, ALARM_REGISTERED, ALARM_RECEIVED, SERVICE_STARTED, SERVICE_FINISHED,
    HTTP_STARTED, HTTP_FINISHED, HTTP_FAILED, WEB_EVENT, BACKGROUND_CHECK, RECOVERY_CHECKED,
    CART_CHECKED, GUARD_CHECKED, NOTIFICATION_DELIVERY
}
enum class DiagnosticStage { PREPARATION, WAITING, SESSION, ACCOUNT, HISTORY, MENU, CHECKOUT, SUBMISSION, VERIFICATION, SCHEDULER, RECEIVER, SERVICE, WEB, BACKGROUND, NOTIFICATION }
enum class DiagnosticNotificationEvent { PREPARATION, SUCCESS, FAILURE }
enum class DiagnosticResult { STARTED, SUCCEEDED, FAILED, BLOCKED, SKIPPED, UNKNOWN, CANCELLED, REGISTERED, IN_FLIGHT }
enum class DiagnosticRoute { HOME, LOGIN, PROFILE, HISTORY, TEMPORARY_ORDER, TEMPORARY_CART, CHECKOUT, SUBMISSION, OTHER }
enum class DiagnosticCode {
    NONE, OTHER, NETWORK, LOGIN_REQUIRED, LOGIN_FAILED, SESSION_CONTRACT, ACCOUNT_UNVERIFIED, ACCOUNT_MISMATCH,
    ORDER_UNAVAILABLE, MAIN_CONTRACT, HISTORY_CONTRACT, MENU_UNVERIFIED, MENU_USED, MENU_EXPIRED,
    TEMP_UNKNOWN, CART_CONFLICT, CHECKOUT_CONTRACT, CHECKOUT_CONFLICT, CHECKOUT_BLOCKED,
    SUBMISSION_UNKNOWN, HTTP, JSON_CONTRACT, EMPTY_RESPONSE, RESPONSE_TOO_LARGE, REDIRECT_BLOCKED,
    DATE_NOT_TODAY, QUANTITY, PRICE_MISSING, PRICE_INVALID, PRICE_MISMATCH, PAYMENT_UNSUPPORTED,
    SERVER_TIME_GATE, TIME_UNAVAILABLE, ADDITIONAL_AUTH, CLOUDBRIC_BLOCKED, VERIFIED,
    GENERATION_CHANGED, LIVE_BLOCKED, LIMIT_EXCEEDED, CANCELLED, CART_EMPTY, CART_CONTRACT, CART_UNVERIFIED,
    NOTIFICATION_APP_DISABLED, NOTIFICATION_CHANNEL_BLOCKED, NOTIFICATION_EVENT_DISABLED, NOTIFICATION_FAILED,
    ACCOUNT_REQUIRED, ALREADY_SUBMITTED, CACHED_RESPONSE, CHECKOUT_UNKNOWN, CHECKOUT_UNVERIFIED, LOGIN_CONTRACT,
    MENU_CONTRACT, PURCHASE_GUARD, SECURITY_BLOCK_CONTRACT, REQUEST_STALE, GUARD, ACCOUNT, SITE_BLOCKED, HISTORY,
    TEMP_INTENT, TEMP_BLOCKED, PRE_TEMP, PRE_SUBMIT_HISTORY, SUBMIT_INTENT, SUBMIT_BLOCKED, LIMIT_CHANGED,
    SUBMIT_UNKNOWN, PREPARATION, ACCOUNT_CHANGED, LOOKUP_EMPTY, LOOKUP_ERROR, HISTORY_CONFLICT, AMOUNT_MISMATCH;

    companion object {
        fun fromSiteCode(value: String?): DiagnosticCode = entries.firstOrNull { it.name == value } ?: OTHER
    }
}

data class DiagnosticEvent(
    val event: DiagnosticEventType,
    val stage: DiagnosticStage? = null,
    val result: DiagnosticResult? = null,
    val code: DiagnosticCode? = null,
    val route: DiagnosticRoute? = null,
    val date: LocalDate? = null,
    val quantity: Int? = null,
    val amount: Long? = null,
    val generation: Long? = null,
    val accountGeneration: Long? = null,
    val httpStatus: Int? = null,
    val durationMillis: Long? = null,
    val targetEpochMillis: Long? = null,
    val submissionPossible: Boolean? = null,
    val accountMatches: Boolean? = null,
    val mainFrame: Boolean? = null,
    val numericCode: Int? = null,
    val dateMatches: Boolean? = null,
    val quantityMatches: Boolean? = null,
    val menuExactMatches: Boolean? = null,
    val menuDecodedMatches: Boolean? = null,
    val notificationsAllowed: Boolean? = null,
    val notificationEvent: DiagnosticNotificationEvent? = null
)

fun interface DiagnosticSink {
    fun record(event: DiagnosticEvent)

    companion object { val NONE = DiagnosticSink { } }
}

fun DiagnosticSink.safeRecord(event: DiagnosticEvent) {
    try { record(event) } catch (_: Throwable) { }
}

fun DiagnosticSink.safe(): DiagnosticSink = DiagnosticSink { safeRecord(it) }

object DiagnosticRun {
    val threadLocal = ThreadLocal<String?>()
    fun newId(): String = UUID.randomUUID().toString()
    fun currentId(): String? = threadLocal.get()?.takeIf(::valid)
    fun contextElement(runId: String = newId()): ThreadContextElement<String?> {
        require(valid(runId))
        return threadLocal.asContextElement(runId)
    }
    fun valid(value: String): Boolean = value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) &&
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
}

enum class DiagnosticStorageError { SETTINGS_READ, SETTINGS_WRITE, READ, WRITE, DELETE, SNAPSHOT, QUEUE_FULL, UNAVAILABLE }
data class DiagnosticStatus(
    val enabled: Boolean = false,
    val lastSavedEpochMillis: Long? = null,
    val bytes: Long = 0,
    val dropped: Long = 0,
    val error: DiagnosticStorageError? = null,
    val busy: Boolean = false
)
data class DiagnosticSnapshot internal constructor(val text: String, val suggestedFileName: String, internal val eraseEpoch: Long)

interface DiagnosticController : DiagnosticSink {
    val status: StateFlow<DiagnosticStatus>
    fun statusSnapshot(): DiagnosticStatus = status.value
    suspend fun setEnabled(enabled: Boolean)
    suspend fun clear()
    suspend fun snapshot(): DiagnosticSnapshot?
    suspend fun cacheSnapshot(snapshot: DiagnosticSnapshot): File?
}
