package com.fullmetalsonic.dosirak.site

import com.fullmetalsonic.dosirak.domain.DatePolicy
import com.fullmetalsonic.dosirak.domain.OrderPlan
import com.fullmetalsonic.dosirak.domain.ReservationReason
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class PoswelClientTest {
    private lateinit var server: MockWebServer
    private lateinit var gateway: PoswelClient
    private val date = LocalDate.of(2026, 10, 9)
    private val plan = OrderPlan(date, 2, LocalTime.of(6, 0), DatePolicy.MANUAL, ReservationReason.NONE)

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        gateway = PoswelClient(OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build(), server.url("/"), true,
            Clock.fixed(Instant.parse("2026-10-08T21:00:00Z"), ZoneId.of("Asia/Seoul")))
    }
    @After fun tearDown() { server.shutdown() }

    private fun fixture(name: String): String = javaClass.getResource("/site/$name.html")!!.readText()
    private fun enqueue(html: String, status: Int = 200) { server.enqueue(MockResponse().setResponseCode(status).setBody(html)) }
    private fun prepare(checkout: String = fixture("checkout"), cart: String = cart()): CheckoutSnapshot {
        enqueue(fixture("main")); enqueue("{\"code\":\"0000\"}"); enqueue(cart); enqueue(checkout)
        return gateway.createCheckout(plan)
    }
    private fun cart(): String = """{"code":"0000","data":[{"me_no":"963","me_date":"10월09일(금)","me_menu":"피자돈까스&소스 + 샐러드","me_quantity":"2","me_price":"10000"}]}"""
    private fun exception(code: String, block: () -> Unit): SiteException {
        try { block() } catch (error: SiteException) { assertEquals(code, error.code); return error }
        throw AssertionError("Expected $code")
    }

    @Test fun sessionReusesLoggedInPageWithoutLoginPost() {
        enqueue(fixture("main")); gateway.ensureSession(null)
        assertEquals(1, server.requestCount); assertEquals("GET", server.takeRequest().method)
    }
    @Test fun expiredSessionNeedsCredentials() {
        enqueue(fixture("login")); exception("LOGIN_REQUIRED") { gateway.ensureSession(null) }
        assertEquals(1, server.requestCount)
    }
    @Test fun loginUsesFreshTokenAndFormEncodingWithoutUncheckedAutoLogin() {
        enqueue(fixture("login")); enqueue("ok"); enqueue(fixture("main"))
        gateway.ensureSession(Credentials("mock&+직번", "mock&+password"))
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/login.check.php", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("uid=mock%26%2B")); assertTrue(body.contains("pwd=mock%26%2Bpassword"))
        assertTrue(body.contains("utk=mock-token")); assertFalse(body.contains("isauto="))
    }
    @Test fun additionalChallengeStopsWithoutLoginPost() {
        enqueue(fixture("login").replace("</form>", "<input name='captcha'></form>"))
        exception("ADDITIONAL_AUTH") { gateway.ensureSession(Credentials("mock", "mock")) }
        assertEquals(1, server.requestCount)
    }
    @Test fun unsafeLoginActionStopsBeforeCredentialsLeaveOrigin() {
        enqueue(fixture("login").replace("/login.check.php", "https://example.org/login.check.php"))
        exception("LOGIN_CONTRACT") { gateway.ensureSession(Credentials("mock", "mock")) }
        assertEquals(1, server.requestCount)
    }
    @Test fun accountVerificationUsesReadOnlyProfileGetBeforeHistory() {
        enqueue("<input type='hidden' name='utk' id='utk' value=''><input id='uid' name='uid' type='text' readonly value=' mock-account '><input id='pwd' name='pwd' type='password' value=''>")
        gateway.verifyAccount("mock-account")
        val request = server.takeRequest(); assertEquals("GET", request.method); assertEquals("/mod.pass.php", request.path)
        assertEquals(1, server.requestCount)
    }
    @Test fun wrongProfileAccountStopsWithoutEchoingEitherIdOrAnyPost() {
        enqueue("<input id='uid' name='uid' type='text' readonly value='private-mock-account'><input id='pwd' name='pwd' type='password'>")
        val error = exception("ACCOUNT_MISMATCH") { gateway.verifyAccount("expected-mock-account") }
        assertFalse(error.submissionPossible); assertFalse(error.message.contains("private-mock-account"))
        assertFalse(error.message.contains("expected-mock-account")); assertEquals(1, server.requestCount)
    }
    @Test fun loginExpiryAtProfileGetIsTypedWithoutAnyPost() {
        enqueue(fixture("login")); assertFalse(exception("LOGIN_REQUIRED") { gateway.verifyAccount("mock-account") }.submissionPossible)
        assertEquals(1, server.requestCount)
    }
    @Test fun duplicateOrMissingReadonlyProfileFieldCannotVerifyAccount() {
        enqueue("<input id='uid' name='uid' type='text' readonly value='mock-account'><input name='uid' type='text' readonly value='mock-account'>")
        exception("ACCOUNT_UNVERIFIED") { gateway.verifyAccount("mock-account") }
        enqueue("<input id='uid' name='uid' type='text' value='mock-account'>")
        exception("ACCOUNT_UNVERIFIED") { gateway.verifyAccount("mock-account") }
        assertEquals(2, server.requestCount)
    }
    @Test fun exactNumericIdAndObservedPcPrefixVerifyWithoutChangingLeadingZeros() {
        for ((profile, expected) in listOf("123456" to "123456", "PC123456" to "123456", "PC001234" to "001234")) {
            enqueue("<input id='uid' name='uid' type='text' readonly value='$profile'>")
            gateway.verifyAccount(expected)
            val request = server.takeRequest()
            assertEquals("GET", request.method); assertEquals("/mod.pass.php", request.path)
        }
        assertEquals(3, server.requestCount)
    }
    @Test fun pcPrefixDoesNotPermitDifferentIdCasePrefixLengthOrNonnumericId() {
        val nonAscii = "\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16"
        val cases = listOf("PC654321" to "123456", "pc123456" to "123456", "XX123456" to "123456",
            "PC123456x" to "123456", "PC 123456" to "123456", "PC12345" to "12345",
            "PC1234567" to "1234567", "PC$nonAscii" to nonAscii, "PC12A456" to "12A456",
            "PC1234" to "001234", "PREFIXPC123456" to "123456", "PCPC123456" to "123456")
        for ((profile, expected) in cases) {
            enqueue("<input id='uid' name='uid' type='text' readonly value='$profile'>")
            val error = exception("ACCOUNT_MISMATCH") { gateway.verifyAccount(expected) }
            assertFalse(error.submissionPossible); assertFalse(error.message.contains(profile)); assertFalse(error.message.contains(expected))
            assertEquals("GET", server.takeRequest().method)
        }
        assertEquals(cases.size, server.requestCount)
    }
    @Test fun pcPrefixDoesNotBypassUniqueReadonlyProfileFieldGuards() {
        for (html in listOf("<input id='uid' name='uid' type='text' value='PC123456'>",
            "<input id='uid' name='uid' type='text' readonly disabled value='PC123456'>",
            "<input id='uid' name='uid' type='text' readonly value='PC123456'><input name='uid' readonly value='PC123456'>")) {
            enqueue(html)
            assertFalse(exception("ACCOUNT_UNVERIFIED") { gateway.verifyAccount("123456") }.submissionPossible)
            assertEquals("GET", server.takeRequest().method)
        }
        assertEquals(3, server.requestCount)
    }
    @Test fun historyIncludesCanceledAndAllOriginalRowsWithoutClickingPaginationOrCancel() {
        enqueue(fixture("history"))
        val rows = gateway.readOrders(date)
        assertEquals(4, rows.size); assertNull(rows[0].quantity); assertNull(rows[0].total)
        assertEquals("827", rows[1].id); assertEquals(2, rows[1].quantity); assertEquals(10000L, rows[1].total)
        assertEquals(1, rows[2].quantity); assertEquals(date.minusDays(1), rows[3].date)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest(); assertEquals("/order.list.php", request.path)
        assertEquals("s=2026-10&e=2026-10", request.body.readUtf8())
    }
    @Test fun blankResponseIsNotAnEmptyHistorySuccess() {
        enqueue("<html></html>"); exception("HISTORY_CONTRACT") { gateway.readOrders(date) }
    }
    @Test fun emptyTbodyIsNotAnEmptyHistorySuccess() {
        enqueue("<table id='dataTable'><tbody></tbody></table>"); exception("HISTORY_CONTRACT") { gateway.readOrders(date) }
    }
    @Test fun explicitNoOrdersRowIsEmptyHistory() {
        enqueue("<table id='dataTable'><tbody><tr><td colspan='5'>주문 내역이 없습니다.</td></tr></tbody></table>")
        assertTrue(gateway.readOrders(date).isEmpty())
    }
    @Test fun loginExpiryDuringHistoryIsTyped() {
        enqueue(fixture("login")); exception("LOGIN_REQUIRED") { gateway.readOrders(date) }
    }
    @Test fun dynamicCheckoutBindsQuantityAndTemporaryIdAndCopiesRecipient() {
        val checkout = prepare()
        assertEquals(2, checkout.quantity); assertEquals(10000L, checkout.total); assertEquals("963", checkout.temporaryId)
        assertEquals(listOf("모의 이름"), checkout.fields["rv_name"])
        assertEquals(listOf("010-0000-0000"), checkout.fields["rv_phone"])
        assertFalse(checkout.fields.containsKey("ck_rq")); assertFalse(checkout.fields.containsKey("sameP"))
        assertFalse(checkout.fields.containsKey("totamt")); assertEquals(listOf("on"), checkout.fields["ck_dispoable"])
        server.takeRequest()
        val temp = server.takeRequest(); assertEquals("/togobox/order.temp.php", temp.path)
        val body = temp.body.readUtf8()
        assertTrue(body.contains("%26")); assertTrue(body.contains("%2B")); assertTrue(body.contains("od_quntity=2"))
        assertTrue(body.contains("lc=mock-location")); assertTrue(body.contains("it=L")); assertTrue(body.contains("allDel=Y"))
        val cartRequest = server.takeRequest(); assertEquals("POST", cartRequest.method); assertEquals(0L, cartRequest.bodySize)
    }
    @Test fun zeroCodeWithoutCartDataStopsBeforeCheckoutAndFinalPost() {
        enqueue(fixture("main")); enqueue("{\"code\":\"0000\"}"); enqueue("{\"code\":\"0000\"}")
        assertTrue(exception("CART_EMPTY") { gateway.createCheckout(plan) }.submissionPossible); assertEquals(3, server.requestCount)
    }
    @Test fun conflictingCartQuantityStopsBeforeCheckout() {
        enqueue(fixture("main")); enqueue("{\"code\":\"0000\"}"); enqueue(cart().replace("\"2\"", "\"1\""))
        assertTrue(exception("CART_CONFLICT") { gateway.createCheckout(plan) }.submissionPossible); assertEquals(3, server.requestCount)
    }
    @Test fun dynamicCheckoutQuantityMismatchStops() {
        exception("CHECKOUT_CONFLICT") { prepare(fixture("checkout").replace("class=\"order-num\" value=\"2\"", "class=\"order-num\" value=\"1\"")) }
        assertEquals(4, server.requestCount)
    }
    @Test fun missingTotalStops() {
        assertTrue(exception("PRICE_MISSING") { prepare(fixture("checkout").replace("id=\"totamt\"", "id=\"missing\"")) }.submissionPossible)
    }
    @Test fun inconsistentTotalStops() {
        exception("PRICE_MISMATCH") { prepare(fixture("checkout").replace("id=\"totamt\" value=\"10,000 원\"", "id=\"totamt\" value=\"20,000 원\"")) }
    }
    @Test fun consistentPriceRiseIsExposedToCoordinatorLimitCheck() {
        val updated = fixture("checkout").replace("value=\"5000\"", "value=\"6000\"").replace("10,000", "12,000")
        val snapshot = prepare(updated); assertEquals(6000L, snapshot.unitPrice); assertEquals(12000L, snapshot.total)
    }
    @Test fun wrongDateOrMealStopsBeforeAnyStateChange() {
        enqueue(fixture("main").replace("'L'", "'D'"))
        assertFalse(exception("MAIN_CONTRACT") { gateway.createCheckout(plan) }.submissionPossible); assertEquals(1, server.requestCount)
    }
    @Test fun rejectedTemporaryCodeDoesNotProveNoStateChange() {
        enqueue(fixture("main")); enqueue("{\"code\":\"9999\"}")
        assertTrue(exception("ORDER_UNAVAILABLE") { gateway.createCheckout(plan) }.submissionPossible)
        assertEquals(2, server.requestCount)
    }
    @Test fun malformedCartJsonAfterTemporaryPostIsAmbiguous() {
        enqueue(fixture("main")); enqueue("{\"code\":\"0000\"}"); enqueue("not json")
        assertTrue(exception("JSON_CONTRACT") { gateway.createCheckout(plan) }.submissionPossible)
        assertEquals(3, server.requestCount)
    }
    @Test fun sessionExpiryAtCheckoutAfterTemporaryPostIsAmbiguous() {
        assertTrue(exception("LOGIN_REQUIRED") { prepare(fixture("login")) }.submissionPossible)
        assertEquals(4, server.requestCount)
    }
    @Test fun finalSubmitExactlyOnceAndReturnsOnlyKnownAlert() {
        val checkout = prepare(); enqueue("<script>alert('주문이 완료 되었습니다.');location.href='/order.list.php';</script>")
        val receipt = gateway.submit(checkout); assertEquals("주문이 완료 되었습니다.", receipt.alert)
        exception("ALREADY_SUBMITTED") { gateway.submit(checkout) }
        assertEquals(5, server.requestCount)
        repeat(4) { server.takeRequest() }
        val final = server.takeRequest(); assertEquals("/togobox/order.reg.php", final.path)
        val body = final.body.readUtf8(); assertTrue(body.contains("uid%5B%5D=963")); assertTrue(body.contains("qty%5B%5D=2")); assertFalse(body.contains("totamt="))
    }
    @Test fun lostFinalReplyIsAmbiguousAndNeverResent() {
        val checkout = prepare(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(exception("SUBMISSION_UNKNOWN") { gateway.submit(checkout) }.submissionPossible)
        exception("ALREADY_SUBMITTED") { gateway.submit(checkout) }; assertEquals(5, server.requestCount)
    }
    @Test fun temporaryReplyLossIsAmbiguousAndPostIsNotRetried() {
        enqueue(fixture("main")); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(exception("SUBMISSION_UNKNOWN") { gateway.createCheckout(plan) }.submissionPossible)
        assertEquals(2, server.requestCount)
    }
    @Test fun finalReadTimeoutIsAmbiguousAndNeverResent() {
        val checkout = prepare(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertTrue(exception("SUBMISSION_UNKNOWN") { gateway.submit(checkout) }.submissionPossible)
        exception("ALREADY_SUBMITTED") { gateway.submit(checkout) }; assertEquals(5, server.requestCount)
    }
    @Test fun futurePlanCannotChangeCart() {
        exception("DATE_NOT_TODAY") { gateway.createCheckout(plan.copy(date = date.plusDays(1))) }
        assertEquals(0, server.requestCount)
    }
    @Test fun postRedirectDoesNotFollowOrResubmit() {
        val checkout = prepare(); server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/togobox/order.reg.php"))
        val receipt = gateway.submit(checkout); assertEquals(307, receipt.responseStatus); assertNull(receipt.alert)
        assertEquals(5, server.requestCount)
    }
    @Test fun arbitraryServerAlertIsNotReturnedOrThrownWithPrivateText() {
        val checkout = prepare(); enqueue("<script>alert('private mock account detail');</script>")
        assertNull(gateway.submit(checkout).alert)
        assertFalse(Credentials("private", "secret").toString().contains("secret"))
        assertFalse(checkout.toString().contains("mock-account"))
    }
    @Test fun foreignConstructedSnapshotCannotBeSubmitted() {
        val original = prepare()
        exception("CHECKOUT_UNVERIFIED") { gateway.submit(original.copy()) }; assertEquals(4, server.requestCount)
    }
    @Test fun rebuildingCartInvalidatesPreviouslyPreparedSnapshot() {
        val original = prepare(); prepare()
        exception("CHECKOUT_UNVERIFIED") { gateway.submit(original) }; assertEquals(8, server.requestCount)
    }
    @Test fun productionConstructorRejectsHttpAndForeignHosts() {
        assertThrows(IllegalArgumentException::class.java) { PoswelClient(OkHttpClient(), "http://dosirak.poswel.co.kr/".toHttpUrl()) }
        assertThrows(IllegalArgumentException::class.java) { PoswelClient(OkHttpClient(), "https://example.org/".toHttpUrl(), true) }
        assertThrows(IllegalArgumentException::class.java) { PoswelClient(OkHttpClient(), server.url("/")) }
    }

    @Test fun cloudbricLoginPost400IsTypedAndNeverRetried() {
        enqueue(fixture("login")); enqueue(fixture("cloudbric-block"), 400)
        val error = exception(CloudbricClassifier.CODE) { gateway.ensureSession(Credentials("mock", "mock")) }
        assertFalse(error.submissionPossible); assertEquals(2, server.requestCount)
        assertFalse(error.message.contains("malformed")); assertTrue(error.message.contains("운영자"))
        assertEquals("/", server.takeRequest().path); assertEquals("/login.check.php", server.takeRequest().path)
    }

    @Test fun cloudbricInitialGet200CannotBecomeAuthenticatedSession() {
        enqueue(fixture("cloudbric-block"))
        assertFalse(exception(CloudbricClassifier.CODE) { gateway.ensureSession(null) }.submissionPossible)
        assertEquals(1, server.requestCount)
    }

    @Test fun cloudbricHistory400And200NeverBecomeEmptySuccessfulHistory() {
        for (status in listOf(400, 200)) {
            enqueue(fixture("cloudbric-block"), status)
            assertFalse(exception(CloudbricClassifier.CODE) { gateway.readOrders(date) }.submissionPossible)
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun cloudbricAccountProfileGetIsTypedBeforeAccountParsing() {
        enqueue(fixture("cloudbric-block"), 400)
        assertFalse(exception(CloudbricClassifier.CODE) { gateway.verifyAccount("mock-account") }.submissionPossible)
        assertEquals(1, server.requestCount)
    }

    @Test fun cloudbricTemporaryPost400PreservesSubmissionPossibility() {
        enqueue(fixture("main")); enqueue(fixture("cloudbric-block"), 400)
        assertTrue(exception(CloudbricClassifier.CODE) { gateway.createCheckout(plan) }.submissionPossible)
        assertEquals(2, server.requestCount)
    }

    @Test fun cloudbricCartPost200AfterTemporaryPostStaysAmbiguous() {
        enqueue(fixture("main")); enqueue("{\"code\":\"0000\"}"); enqueue(fixture("cloudbric-block"))
        assertTrue(exception(CloudbricClassifier.CODE) { gateway.createCheckout(plan) }.submissionPossible)
        assertEquals(3, server.requestCount)
    }

    @Test fun cloudbricCheckoutGet200AfterTemporaryPostStaysAmbiguous() {
        assertTrue(exception(CloudbricClassifier.CODE) { prepare(checkout = fixture("cloudbric-block")) }.submissionPossible)
        assertEquals(4, server.requestCount)
    }

    @Test fun cloudbricFinalPost400And200PreserveIntentAndNeverResubmit() {
        for ((index, status) in listOf(400, 200).withIndex()) {
            val checkout = prepare(); enqueue(fixture("cloudbric-block"), status)
            assertTrue(exception(CloudbricClassifier.CODE) { gateway.submit(checkout) }.submissionPossible)
            exception("ALREADY_SUBMITTED") { gateway.submit(checkout) }
            assertEquals((index + 1) * 5, server.requestCount)
        }
    }

    @Test fun genericHttp400RetainsOrdinaryHttpCode() {
        enqueue("<html><body><h1>400 Bad Request</h1><p>Invalid request.</p></body></html>", 400)
        assertFalse(exception("HTTP") { gateway.readOrders(date) }.submissionPossible)
    }

    @Test fun normalHistoryMentioningCloudbricAnd400RemainsReadable() {
        enqueue(fixture("history") + "<p>Cloudbric 400 Bad Request blocked troubleshooting.</p>")
        assertEquals(4, gateway.readOrders(date).size)
    }

    @Test fun observedDynamicLoginPostsOnceWithFreshTokenAndFormEncodedCredentials() {
        enqueue(fixture("login-dynamic")); enqueue("ok"); enqueue(fixture("main"))
        gateway.ensureSession(Credentials("mock&+직번", "mock&+password"))
        assertEquals(3, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
        val request = server.takeRequest()
        assertEquals("POST", request.method); assertEquals("/login.check.php", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("uid=mock%26%2B")); assertTrue(body.contains("pwd=mock%26%2Bpassword"))
        assertTrue(body.contains("utk=mock-dynamic-token")); assertFalse(body.contains("isauto="))
        assertEquals("GET", server.takeRequest().method)
    }

    @Test fun observedDynamicLoginChallengeStopsBeforeAnyPasswordPost() {
        enqueue(fixture("login-dynamic").replace("</form>", "<input name='captcha'></form>"))
        assertFalse(exception("ADDITIONAL_AUTH") { gateway.ensureSession(Credentials("mock", "mock")) }.submissionPossible)
        assertEquals(1, server.requestCount)
    }

    @Test fun observedDynamicLoginUnsafeContractStopsBeforeAnyPasswordPost() {
        for ((index, html) in listOf(
            fixture("login-dynamic").replace("action=\"\"", "action=\"https://example.org/login.check.php\""),
            fixture("login-dynamic").replace("value=\"mock-dynamic-token\"", "value=\"\""),
            fixture("login-dynamic").replace("</form>", "<input name='uid'></form>")
        ).withIndex()) {
            enqueue(html)
            assertFalse(exception("LOGIN_CONTRACT") { gateway.ensureSession(Credentials("mock", "mock")) }.submissionPossible)
            assertEquals(index + 1, server.requestCount)
        }
    }

    @Test fun observedDynamicLoginCloudbricBlockStillStopsAfterOnePasswordPost() {
        enqueue(fixture("login-dynamic")); enqueue(fixture("cloudbric-block"), 400)
        assertFalse(exception(CloudbricClassifier.CODE) { gateway.ensureSession(Credentials("mock", "mock")) }.submissionPossible)
        assertEquals(2, server.requestCount)
        assertEquals("GET", server.takeRequest().method); assertEquals("POST", server.takeRequest().method)
    }
}
