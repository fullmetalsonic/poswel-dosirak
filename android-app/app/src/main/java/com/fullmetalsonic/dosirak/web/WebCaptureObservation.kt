package com.fullmetalsonic.dosirak.web

import com.fullmetalsonic.dosirak.diagnostics.*

internal object WebCaptureObservation {
    fun fromMessage(message: WebCaptureMessage): SafeDetailObservation? {
        if (message.type == WebCaptureMessage.Type.READY) return null
        if (message.type == WebCaptureMessage.Type.HISTORY) return SafeDetailObservation(
            kind = DetailKind.STRUCTURE, route = DiagnosticRoute.HISTORY, stage = DiagnosticStage.WEB,
            coverage = if (message.history?.partial == false) DetailCoverage.COMPLETE else DetailCoverage.PARTIAL,
            omissions = setOf(DetailOmission.PERSONAL_FIELDS_EXCLUDED) +
                (if (message.history?.partial == true) setOf(DetailOmission.STRUCTURE_NOT_EXTRACTED) else emptySet()),
            history = message.history, historyScope = DetailHistoryScope.CURRENT_DOCUMENT_ONLY, source = DetailSource.WEB_OBSERVED)
        if (message.type == WebCaptureMessage.Type.CHECKOUT) return SafeDetailObservation(
            kind = DetailKind.BUSINESS, route = DiagnosticRoute.CHECKOUT, stage = DiagnosticStage.WEB,
            coverage = if (message.business?.let { it.targetDate != null && it.quantity != null && it.total != null } == true)
                DetailCoverage.COMPLETE else DetailCoverage.PARTIAL,
            omissions = setOf(DetailOmission.PERSONAL_FIELDS_EXCLUDED), business = message.business,
            webPhase = DetailWebPhase.CHECKOUT_BEFORE, source = DetailSource.WEB_OBSERVED)
        val route = when (message.route) {
            WebCaptureMessage.Route.CANCEL -> DiagnosticRoute.CANCELLATION
            WebCaptureMessage.Route.TEMP -> DiagnosticRoute.TEMPORARY_ORDER
            WebCaptureMessage.Route.CART -> DiagnosticRoute.TEMPORARY_CART
            WebCaptureMessage.Route.SUBMIT -> DiagnosticRoute.SUBMISSION
            null -> return null
        }
        val formIntent = message.type == WebCaptureMessage.Type.FORM
        return SafeDetailObservation(
            // An intent is not an observed HTTP request, acceptance, or order completion.
            kind = if (formIntent) DetailKind.FORM_INTENT else DetailKind.HTTP,
            route = route, stage = DiagnosticStage.WEB,
            result = if (formIntent) DiagnosticResult.STARTED else when (message.success) {
                true -> DiagnosticResult.SUCCEEDED; false -> DiagnosticResult.FAILED; null -> DiagnosticResult.UNKNOWN
            },
            coverage = if (formIntent) DetailCoverage.PARTIAL else DetailCoverage.HTTP_ONLY,
            omissions = setOf(DetailOmission.PERSONAL_FIELDS_EXCLUDED, DetailOmission.STRUCTURE_NOT_EXTRACTED),
            method = when (message.method) { "GET" -> DetailMethod.GET; "POST" -> DetailMethod.POST; else -> DetailMethod.OTHER },
            httpStatus = if (formIntent) null else message.status?.takeIf { it >= 100 },
            durationMillis = if (formIntent) null else message.durationMillis,
            source = DetailSource.WEB_OBSERVED
        )
    }
}
