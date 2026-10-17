package com.fullmetalsonic.dosirak.diagnostics

import java.time.LocalDate

enum class DetailKind { SEGMENT_STARTED, SEGMENT_FINISHED, RUN_STARTED, RUN_FINISHED, HTTP, FORM_INTENT, STRUCTURE, BUSINESS, GUARD, CLOCK, ENVIRONMENT, ALARM, SERVICE, NOTIFICATION }
enum class DetailCoverage { COMPLETE, PARTIAL, HTTP_ONLY, NOT_REACHED, REACHED_NOT_EXTRACTED }
enum class DetailOmission { PARTIAL_START, PERSONAL_FIELDS_EXCLUDED, UNKNOWN_FIELDS_EXCLUDED, MESSAGE_OMITTED, STRUCTURE_NOT_EXTRACTED, NODE_LIMIT, DEPTH_LIMIT, FIELD_LIMIT, OPTION_LIMIT, HISTORY_ROW_LIMIT, BYTE_LIMIT, TIME_LIMIT, RUN_TIME_LIMIT, QUEUE_FULL, STORAGE_LIMIT, ROTATED, CORRUPT, UNSUPPORTED_SCHEMA, EXTRACTION_FAILED, UNCLOSED, CLOCK_ANOMALY }
enum class DetailMethod { GET, POST, OTHER }
enum class SafeFieldName { UID, PWD, QTY, QUANTITY, DATE, ORDER_DATE, PRICE, TOTAL, MENU, DELIVERY, PAYMENT, SUBMIT, OTHER_KNOWN }
enum class SafeFieldType { TEXT, PASSWORD, HIDDEN, NUMBER, CHECKBOX, RADIO, SELECT, SUBMIT, BUTTON, DATE, OTHER }
enum class SafeJsonKey { CODE, RESULT, SUCCESS, DATA, LIST, ITEMS, DATE, ORDER_DATE, QTY, QUANTITY, PRICE, TOTAL, AMOUNT, MENU, MESSAGE }
enum class SafeJsonType { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }
enum class DetailNetwork { NONE, WIFI, CELLULAR, ETHERNET, VPN, OTHER, UNKNOWN }
enum class DetailTimeZone { ASIA_SEOUL, UTC, OTHER, UNKNOWN }
enum class DetailClockSource { SITE, HTTP_DATE, DEVICE, UNKNOWN }
enum class DetailClockDecision { ACCEPTED, REJECTED, EXPIRED, UNAVAILABLE, UNKNOWN }
enum class DetailRedirect { NONE, ALLOWED, BLOCKED, UNKNOWN }
enum class DetailContentType { HTML, JSON, TEXT, OTHER, UNKNOWN }
enum class DetailSource { NATIVE, WEB_OBSERVED }
enum class SafeHistoryStatus { ORDERED, DELIVERED, CANCELLED, UNKNOWN }
enum class SafeHistoryHandler { JS_ORDER_CXL }
enum class DetailWebPhase { HISTORY_BEFORE, CHECKOUT_BEFORE, ORDER_INTENT, ORDER_RESULT, HISTORY_AFTER_ORDER, CANCEL_RESULT, HISTORY_AFTER_CANCEL, HISTORY_VIEW }
enum class DetailSiteAlert { ORDER_COMPLETED, CANCEL_COMPLETED, ORDER_CLOSED, LOGIN_REQUIRED, QUANTITY_LIMIT, EMPTY_CART, UNKNOWN_OMITTED }
enum class DetailHistoryScope { CURRENT_DOCUMENT_ONLY }

/** Source HTML facts, not rendered/mobile visibility or authorization to cancel. */
data class SafeHistoryRow(val date: LocalDate? = null, val quantity: Int? = null, val total: Long? = null,
    val status: SafeHistoryStatus = SafeHistoryStatus.UNKNOWN, val cancelCellPresent: Boolean = false,
    val cancelTimePresent: Boolean = false, val cancelLinkPresent: Boolean = false,
    val cancelHandler: SafeHistoryHandler? = null, val ratingLinkPresent: Boolean = false)

data class SafeHistoryStructure(val tablePresent: Boolean = false, val bodyPresent: Boolean = false,
    val rowsExamined: Int = 0, val allRowsScanned: Boolean = false, val rows: List<SafeHistoryRow> = emptyList(),
    val partial: Boolean = false)

data class SafeFormField(val name: SafeFieldName? = null, val unknownFieldSlot: Int? = null,
    val type: SafeFieldType = SafeFieldType.OTHER, val required: Boolean = false, val readOnly: Boolean = false,
    val minimum: Int? = null, val maximum: Int? = null, val optionCount: Int = 0,
    val quantityOptions: List<Int> = emptyList(), val valueExcluded: Boolean = true, val disabled: Boolean = false,
    val actionRoute: DiagnosticRoute? = null, val actionMethod: DetailMethod? = null)
data class SafeFormStructure(val actionRoute: DiagnosticRoute? = null, val method: DetailMethod = DetailMethod.OTHER,
    val fields: List<SafeFormField> = emptyList(), val accountFieldPresent: Boolean? = null,
    val personalValuesExcluded: Boolean = true, val omittedUnknownCount: Int = 0, val partial: Boolean = false)
data class SafeJsonNode(val key: SafeJsonKey? = null, val unknownKeySlot: Int? = null,
    val type: SafeJsonType = SafeJsonType.NULL, val count: Int? = null, val children: List<SafeJsonNode> = emptyList())
data class SafeJsonStructure(val root: SafeJsonNode, val omittedUnknownCount: Int = 0, val partial: Boolean = false)
data class SafeBusinessObservation(val targetDate: LocalDate? = null, val quantity: Int? = null,
    val unitPrice: Long? = null, val total: Long? = null, val minimumQuantity: Int? = null, val maximumQuantity: Int? = null,
    val quantityOptions: List<Int> = emptyList(), val available: Boolean? = null, val dateMatches: Boolean? = null,
    val quantityMatches: Boolean? = null, val amountMatches: Boolean? = null, val menuMatches: Boolean? = null,
    val accountMatches: Boolean? = null, val resultCount: Int? = null, val deliveryMatches: Boolean? = null,
    val profilePresent: Boolean? = null)
data class SafeEnvironmentObservation(val apiLevel: Int? = null, val timeZone: DetailTimeZone = DetailTimeZone.UNKNOWN,
    val exactAlarmAllowed: Boolean? = null, val notificationPermission: Boolean? = null,
    val appNotificationsAllowed: Boolean? = null, val channelNotificationsAllowed: Boolean? = null,
    val batteryOptimizationExempt: Boolean? = null, val backgroundRestricted: Boolean? = null,
    val network: DetailNetwork = DetailNetwork.UNKNOWN)
data class SafeClockObservation(val source: DetailClockSource = DetailClockSource.UNKNOWN,
    val decision: DetailClockDecision = DetailClockDecision.UNKNOWN, val sampleEpochMillis: Long? = null,
    val sampleAgeMillis: Long? = null, val roundTripMillis: Long? = null, val offsetMillis: Long? = null,
    val earliestEpochMillis: Long? = null, val latestEpochMillis: Long? = null, val ttlMillis: Long? = null)

/** Contains only app-generated correlation IDs. Raw site values cannot enter the capture contract. */
data class DetailCapture internal constructor(val processInstanceId: String, val runId: String,
    val requestId: String?, val requestOrdinal: Int?, val parentRequestId: String?, val captureSegmentId: String,
    internal val epoch: Long)

data class SafeDetailObservation(val kind: DetailKind, val route: DiagnosticRoute? = null,
    val stage: DiagnosticStage? = null, val result: DiagnosticResult? = null, val code: DiagnosticCode? = null,
    val coverage: DetailCoverage = DetailCoverage.COMPLETE, val omissions: Set<DetailOmission> = emptySet(),
    val method: DetailMethod? = null, val httpStatus: Int? = null, val responseBytes: Long? = null,
    val durationMillis: Long? = null, val redirectCount: Int? = null, val redirect: DetailRedirect? = null,
    val contentType: DetailContentType? = null, val httpDateEpochMillis: Long? = null, val retryAfterMillis: Long? = null,
    val authenticated: Boolean? = null, val completionAlertMatched: Boolean? = null, val submissionPossible: Boolean? = null,
    val form: SafeFormStructure? = null, val json: SafeJsonStructure? = null, val business: SafeBusinessObservation? = null,
    val environment: SafeEnvironmentObservation? = null, val clock: SafeClockObservation? = null,
    val targetEpochMillis: Long? = null, val generation: Long? = null, val accountGeneration: Long? = null,
    val parentRunId: String? = null, val extractionNanos: Long? = null, val visitedNodes: Int? = null,
    val notificationEvent: DiagnosticNotificationEvent? = null, val apiReturned: Boolean? = null,
    val history: SafeHistoryStructure? = null, val source: DetailSource? = null,
    val webPhase: DetailWebPhase? = null, val siteAlert: DetailSiteAlert? = null,
    val historyScope: DetailHistoryScope? = null)

fun DiagnosticSink.safeDetail(capture: DetailCapture?, detail: SafeDetailObservation) {
    if (capture != null) try { recordDetail(capture, detail) } catch (_: Throwable) { }
}
