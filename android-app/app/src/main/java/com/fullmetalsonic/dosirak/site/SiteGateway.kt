package com.fullmetalsonic.dosirak.site

import com.fullmetalsonic.dosirak.domain.OrderPlan
import com.fullmetalsonic.dosirak.domain.ServerTimeSample
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

interface MenuSnapshot {
    val date: LocalDate
    val quantity: Int
    val generation: Long
    val accountGeneration: Long
}

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
    fun probeServerTime(): ServerTimeSample {
        throw SiteException("TIME_UNAVAILABLE", "사이트 시각을 확인할 수 없습니다.")
    }
    fun loadMenu(plan: OrderPlan, generation: Long, accountGeneration: Long): MenuSnapshot? = null
    fun createCheckout(plan: OrderPlan): CheckoutSnapshot
    fun createCheckout(plan: OrderPlan, menu: MenuSnapshot?): CheckoutSnapshot = createCheckout(plan)
    fun createCheckout(plan: OrderPlan, menu: MenuSnapshot?, beforeMutation: () -> Unit): CheckoutSnapshot {
        beforeMutation()
        return createCheckout(plan, menu)
    }
    fun submit(checkout: CheckoutSnapshot): SubmitReceipt
    fun submit(checkout: CheckoutSnapshot, beforeMutation: () -> Unit): SubmitReceipt {
        beforeMutation()
        return submit(checkout)
    }
}
