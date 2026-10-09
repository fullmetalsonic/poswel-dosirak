package com.fullmetalsonic.dosirak.web

import java.net.URI

object WebNavigation {
    const val HOME = "https://dosirak.poswel.co.kr/"

    fun allowedUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host.equals("dosirak.poswel.co.kr", ignoreCase = true) &&
            uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)

    fun target(path: String): String {
        val value = if (path.startsWith("/")) HOME.dropLast(1) + path else path
        if (!allowedUrl(value)) return HOME
        return when (WebDiagnostic.route(value)) {
            WebRoute.LOGIN, WebRoute.CHECKOUT, WebRoute.TEMP_ORDER, WebRoute.SUBMIT -> HOME
            else -> value
        }
    }

    fun canRestoreHistory(urls: List<String>, currentMethod: WebMethod): Boolean =
        currentMethod == WebMethod.GET && urls.isNotEmpty() && urls.all { it == HOME }

    fun handledAfterRestore(handledRequest: Int, currentRequest: Int): Int =
        if (currentRequest == 0) 0 else handledRequest

    fun shouldGet(request: Int, handledRequest: Int, busy: Boolean, viewReady: Boolean,
        initialLoadPending: Boolean): Boolean = viewReady && !busy &&
        (initialLoadPending || (request != 0 && request != handledRequest))
}
