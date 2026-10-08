package com.fullmetalsonic.dosirak.site

import com.fullmetalsonic.dosirak.domain.OrderPlan
import java.time.LocalDate

data class Credentials(val userId: String, val password: String) {
    override fun toString(): String = "Credentials([redacted])"
}

data class SiteOrder(
    val id: String?, val date: LocalDate, val quantity: Int?, val status: String,
    val total: Long?, val menu: String
)

data class CheckoutSnapshot(
    val date: LocalDate, val quantity: Int, val unitPrice: Long, val total: Long,
    val accountId: String, val temporaryId: String, val fields: Map<String, List<String>>
) {
    override fun toString(): String = "CheckoutSnapshot(date=$date, quantity=$quantity, unitPrice=$unitPrice, total=$total, fields=[redacted])"
}

data class SubmitReceipt(val alert: String?, val responseStatus: Int)

class SiteException(
    val code: String, override val message: String, val submissionPossible: Boolean = false
) : Exception(message)

/** Blocking calls. The coordinator owns IO dispatching and durable submission state. */
interface SiteGateway {
    fun ensureSession(credentials: Credentials?)
    fun verifyAccount(expectedId: String) {
        throw SiteException("ACCOUNT_UNVERIFIED", "사이트 계정을 확인할 수 없습니다.")
    }
    fun readOrders(date: LocalDate): List<SiteOrder>
    fun createCheckout(plan: OrderPlan): CheckoutSnapshot
    fun submit(checkout: CheckoutSnapshot): SubmitReceipt
}
