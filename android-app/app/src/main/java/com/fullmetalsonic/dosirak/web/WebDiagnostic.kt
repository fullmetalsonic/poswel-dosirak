package com.fullmetalsonic.dosirak.web

import java.net.URI
import java.util.Locale

enum class WebRoute { HOME, LOGIN, ACCOUNT, HISTORY, CHECKOUT, TEMP_ORDER, SUBMIT, OTHER_TRUSTED, EXTERNAL, INVALID }
enum class WebMethod { GET, POST, HEAD, OTHER, UNKNOWN }
enum class WebEvent { REQUEST, PAGE_STARTED, PAGE_FINISHED, HTTP_ERROR, RESOURCE_ERROR, SSL_ERROR, HOME_GET, APP_GET }
enum class LoginCheckCode {
    UNKNOWN, CHECKING, VERIFIED, LOGIN_REQUIRED, LOGIN_FAILED, LOGIN_CONTRACT,
    ACCOUNT_UNVERIFIED, ACCOUNT_MISMATCH, SECURITY_BLOCK_CONTRACT, NETWORK, OTHER;

    companion object {
        fun fromSiteCode(value: String): LoginCheckCode = entries.firstOrNull { it.name == value } ?: OTHER
    }
}

data class WebDiagnostic(
    val timestampMillis: Long,
    val event: WebEvent,
    val route: WebRoute,
    val method: WebMethod,
    val mainFrame: Boolean?,
    val code: Int?,
    val providerVersion: String
) {
    fun safeLogLine(): String = "time=$timestampMillis event=${event.name} route=${route.name} method=${method.name}" +
        " mainFrame=${mainFrame ?: "unknown"} code=${code ?: "none"} provider=${safeVersion(providerVersion)}"

    companion object {
        fun route(url: String): WebRoute {
            val uri = runCatching { URI(url) }.getOrNull() ?: return WebRoute.INVALID
            if (!WebNavigation.allowedUrl(url)) return WebRoute.EXTERNAL
            return when (uri.rawPath) {
                "", "/" -> WebRoute.HOME
                "/login.check.php" -> WebRoute.LOGIN
                "/mod.pass.php" -> WebRoute.ACCOUNT
                "/order.list.php" -> WebRoute.HISTORY
                "/order.info.php" -> WebRoute.CHECKOUT
                "/togobox/order.temp.php", "/togobox/get.order.temp.php" -> WebRoute.TEMP_ORDER
                "/togobox/order.reg.php" -> WebRoute.SUBMIT
                else -> WebRoute.OTHER_TRUSTED
            }
        }

        fun method(value: String): WebMethod = when (value.uppercase(Locale.ROOT)) {
            "GET" -> WebMethod.GET
            "POST" -> WebMethod.POST
            "HEAD" -> WebMethod.HEAD
            else -> WebMethod.OTHER
        }

        fun safeVersion(value: String): String = if (value.matches(Regex("[A-Za-z0-9._-]{1,80}"))) value else "unknown"
    }
}
