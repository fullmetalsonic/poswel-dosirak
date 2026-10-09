package com.fullmetalsonic.dosirak.site

import com.fullmetalsonic.dosirak.domain.OrderPlan
import com.fullmetalsonic.dosirak.domain.ServerOrderClock
import com.fullmetalsonic.dosirak.domain.ServerTimeSample
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.Authenticator
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.Clock
import java.time.ZoneId
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class PoswelClient(
    val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://dosirak.poswel.co.kr/".toHttpUrl(),
    private val testMode: Boolean = false,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallMillis: () -> Long = { System.currentTimeMillis() }
) : SiteGateway {
    private val transport = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).build()
    private val submitted = Collections.newSetFromMap(IdentityHashMap<CheckoutSnapshot, Boolean>())
    private data class PreparedCheckout(val snapshot: CheckoutSnapshot, val time: LocalTime)
    private class LoadedMenu(
        override val date: LocalDate, override val quantity: Int, override val generation: Long,
        override val accountGeneration: Long, val owner: Any, val loadedElapsed: Long,
        val arguments: List<String>, val fields: Map<String, List<String>>
    ) : MenuSnapshot
    private val prepared = IdentityHashMap<CheckoutSnapshot, PreparedCheckout>()
    private val menuOwner = Any()
    private var activeMenu: LoadedMenu? = null

    init {
        val production = baseUrl.scheme == "https" && baseUrl.host == "dosirak.poswel.co.kr" && baseUrl.port == 443
        val localTest = testMode && baseUrl.host in setOf("localhost", "127.0.0.1", "::1")
        require((production || localTest) && baseUrl.encodedPath == "/" && baseUrl.username.isEmpty() &&
            baseUrl.password.isEmpty() && baseUrl.query == null && baseUrl.fragment == null) { "허용되지 않은 사이트 주소입니다." }
    }

    @Synchronized
    override fun ensureSession(credentials: Credentials?) {
        val initial = get("/")
        val doc = SiteParser.document(initial.html)
        SiteParser.checkChallenge(doc)
        if (SiteParser.isAuthenticated(doc)) return
        if (!SiteParser.isLogin(doc)) throw SiteException("SESSION_CONTRACT", "로그인 상태를 확인할 수 없습니다.")
        if (credentials == null) throw SiteException("LOGIN_REQUIRED", "사이트 로그인이 필요합니다.")
        prepared.clear()
        activeMenu = null
        val fields = LoginContractParser.fields(doc, baseUrl).mapValues { it.value.toMutableList() }.toMutableMap()
        val token = fields["utk"]?.singleOrNull()
        if (token.isNullOrBlank() || credentials.userId.isBlank() || credentials.password.isBlank()) throw SiteException("LOGIN_CONTRACT", "로그인 정보를 확인해 주세요.")
        fields["uid"] = mutableListOf(credentials.userId)
        fields["pwd"] = mutableListOf(credentials.password)
        val response = post("/login.check.php", fields, false)
        if (response.status !in 200..399) throw SiteException("LOGIN_FAILED", "사이트 로그인을 완료하지 못했습니다.")
        val after = SiteParser.document(get("/").html)
        SiteParser.checkChallenge(after)
        if (!SiteParser.isAuthenticated(after)) throw SiteException("LOGIN_FAILED", "사이트 로그인을 완료하지 못했습니다.")
    }

    override fun readOrders(date: LocalDate): List<SiteOrder> {
        val month = "%04d-%02d".format(java.util.Locale.ROOT, date.year, date.monthValue)
        val result = post("/order.list.php", mapOf("s" to listOf(month), "e" to listOf(month)), false)
        if (result.status in 300..399) throw SiteException("LOGIN_REQUIRED", "주문내역을 다시 로그인하여 확인해 주세요.")
        requireSuccess(result)
        return SiteParser.orders(SiteParser.document(result.html))
    }

    @Synchronized
    override fun verifyAccount(expectedId: String) {
        if (expectedId.isBlank()) throw SiteException("ACCOUNT_UNVERIFIED", "확인할 계정이 없습니다.")
        val doc = SiteParser.document(get("/mod.pass.php").html)
        SiteParser.checkChallenge(doc)
        if (SiteParser.isLogin(doc)) throw SiteException("LOGIN_REQUIRED", "사이트 로그인이 필요합니다.")
        val identifiers = doc.select("input[name=uid]")
        val input = identifiers.singleOrNull()
        if (input == null || input.id() != "uid" || input.attr("type").lowercase() != "text" ||
            !input.hasAttr("readonly") || input.hasAttr("disabled") || input.attr("value").trim().isEmpty()) {
            throw SiteException("ACCOUNT_UNVERIFIED", "사이트 계정정보의 직번을 확인할 수 없습니다.")
        }
        val profileId = input.attr("value").trim()
        if (!matchesStoredAccount(profileId, expectedId)) {
            prepared.clear()
            activeMenu = null
            throw SiteException("ACCOUNT_MISMATCH", "사이트 로그인 계정과 앱의 저장 계정이 다릅니다.")
        }
    }

    override fun probeServerTime(): ServerTimeSample {
        val result = get("/", requireFresh = true, allowRedirects = false, callDeadlineMillis = 2_000)
        if (result.status != 200) throw SiteException("TIME_UNAVAILABLE", "정상 사이트 시각 응답이 아닙니다.")
        val doc = SiteParser.document(result.html)
        SiteParser.checkChallenge(doc)
        if (SiteParser.isLogin(doc)) throw SiteException("LOGIN_REQUIRED", "사이트 로그인이 필요합니다.")
        if (!SiteParser.isAuthenticated(doc)) throw SiteException("TIME_UNAVAILABLE", "로그인된 사이트 시각을 확인할 수 없습니다.")
        return ServerTimeSample(ServerTimeParser.parse(result.html), result.requestStartedElapsed,
            result.responseFinishedElapsed, result.deviceWallMillis)
    }

    @Synchronized
    override fun loadMenu(plan: OrderPlan, generation: Long, accountGeneration: Long): MenuSnapshot {
        prepared.clear()
        activeMenu = null
        if (plan.quantity !in 1..5) throw SiteException("QUANTITY", "수량은 1~5개여야 합니다.")
        purchaseGuard(plan.date, plan.time)
        if (plan.date != LocalDate.now(clock)) throw SiteException("DATE_NOT_TODAY", "당일 도시락만 신청할 수 있습니다.")
        val home = get("/", requireFresh = true)
        if (home.status != 200) throw SiteException("MAIN_CONTRACT", "정상 메뉴 조회 응답이 아닙니다.")
        val doc = SiteParser.document(home.html)
        if (SiteParser.isLogin(doc)) throw SiteException("LOGIN_REQUIRED", "사이트 로그인이 필요합니다.")
        SiteParser.checkChallenge(doc)
        val button = doc.getElementById("go-pay") ?: throw SiteException("ORDER_UNAVAILABLE", "현재 신청 가능한 도시락이 없습니다.")
        if (button.hasAttr("disabled") || button.hasClass("disabled")) throw SiteException("ORDER_UNAVAILABLE", "현재 신청이 마감되었습니다.")
        val args = ScriptLiterals.arguments(button.attr("onclick"), "goPay")
            ?: throw SiteException("MAIN_CONTRACT", "바로결제 상품 정보를 확인할 수 없습니다.")
        if (args.size != 7 || !args[0].matches(Regex("[0-9]+")) || args[1].isBlank() || args[2].isBlank() || SiteParser.date(args[3]) != plan.date || args[5] != "L" || args[6] !in setOf("this", "$(this)")) {
            throw SiteException("MAIN_CONTRACT", "신청 날짜와 점심 상품 연결을 확인할 수 없습니다.")
        }
        val unit = SiteParser.amount(args[4])?.takeIf { it > 0 } ?: throw SiteException("MAIN_CONTRACT", "상품 금액을 확인할 수 없습니다.")
        if (doc.getElementById("qty_${args[0]}") == null || SiteParser.amount(doc.getElementById("priceEach_${args[0]}")?.attr("value") ?: "") != unit) throw SiteException("MAIN_CONTRACT", "상품 금액·수량 연결을 확인할 수 없습니다.")
        val location = SiteParser.selectedValue(doc.getElementById("loc")) ?: throw SiteException("MAIN_CONTRACT", "배송 지역 선택을 확인할 수 없습니다.")
        val section = SiteParser.selectedValue(doc.getElementById("sec")) ?: throw SiteException("MAIN_CONTRACT", "도시락 종류 선택을 확인할 수 없습니다.")
        val fields = mapOf(
            "od_date" to listOf(args[3]), "od_menu_kind" to listOf(args[1]), "od_menu_name" to listOf(args[2]),
            "od_menu_price" to listOf(args[4]), "od_uid" to listOf(args[0]), "od_quntity" to listOf(plan.quantity.toString()),
            "lc" to listOf(location), "sc" to listOf(section), "it" to listOf(args[5]), "allDel" to listOf("Y")
        )
        return LoadedMenu(plan.date, plan.quantity, generation, accountGeneration, menuOwner,
            home.responseFinishedElapsed, args.toList(), fields).also { activeMenu = it }
    }

    @Synchronized
    override fun createCheckout(plan: OrderPlan): CheckoutSnapshot = createCheckout(plan, null) {}

    @Synchronized
    override fun createCheckout(plan: OrderPlan, menu: MenuSnapshot?): CheckoutSnapshot = createCheckout(plan, menu) {}

    @Synchronized
    override fun createCheckout(plan: OrderPlan, menu: MenuSnapshot?, beforeMutation: () -> Unit): CheckoutSnapshot {
        val loaded = (menu ?: loadMenu(plan, 0, 0)) as? LoadedMenu
            ?: throw SiteException("MENU_UNVERIFIED", "검증된 메뉴 정보가 아닙니다.")
        if (loaded.owner !== menuOwner) throw SiteException("MENU_UNVERIFIED", "다른 연결에서 조회한 메뉴 정보입니다.")
        if (activeMenu !== loaded) throw SiteException("MENU_USED", "이미 사용했거나 갱신된 메뉴 정보입니다.")
        if (loaded.date != plan.date || loaded.quantity != plan.quantity) throw SiteException("MENU_UNVERIFIED", "신청 날짜·수량과 메뉴 정보가 다릅니다.")
        menuFreshness(loaded)
        activeMenu = null
        prepared.clear()
        var attempted = false
        // From this boundary onward, even a rejected code or empty cart cannot prove no state change.
        try {
            val temp = post("/togobox/order.temp.php", loaded.fields, true) {
                beforeMutation()
                menuFreshness(loaded)
                purchaseGuard(plan.date, plan.time)
                if (plan.date != LocalDate.now(clock)) throw SiteException("DATE_NOT_TODAY", "당일 도시락만 신청할 수 있습니다.")
                attempted = true
            }
            val tempJson = json(temp, true)
            if (tempJson.get("code")?.takeIf { it.isJsonPrimitive }?.asString != "0000") throw SiteException("ORDER_UNAVAILABLE", "임시 주문을 완료하지 못했습니다.")
            val cart = json(post("/togobox/get.order.temp.php", emptyMap(), true), true)
            if (cart.get("code")?.takeIf { it.isJsonPrimitive }?.asString != "0000") throw SiteException("CART_UNVERIFIED", "임시 주문 목록을 확인할 수 없습니다.")
            val data = cart.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: throw SiteException("CART_EMPTY", "임시 주문 항목이 없습니다.")
            if (data.size() != 1 || !data[0].isJsonObject) throw SiteException("CART_CONFLICT", "임시 주문 항목이 한 개로 확인되지 않습니다.")
            val item = data[0].asJsonObject
            if (SiteParser.amount(item.string("me_price") ?: "")?.takeIf { it > 0 } == null) throw SiteException("PRICE_MISSING", "임시 주문 표시 금액이 없습니다.")
            val cartDate = item.string("me_date") ?: throw SiteException("CART_CONTRACT", "임시 주문 날짜가 없습니다.")
            val matchedDate = SiteParser.date(cartDate) ?: Regex("([0-9]{1,2})월\\s*([0-9]{1,2})일").find(cartDate)?.let { m ->
                runCatching { LocalDate.of(plan.date.year, m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
            }
            if (matchedDate != plan.date || item.string("me_quantity")?.toIntOrNull() != plan.quantity || item.string("me_menu") != loaded.arguments[2]) throw SiteException("CART_CONFLICT", "임시 주문 날짜·수량·메뉴가 일치하지 않습니다.")
            val temporaryId = item.string("me_no")?.takeIf { it.matches(Regex("[0-9]+")) } ?: throw SiteException("CART_CONTRACT", "임시 주문 식별자를 확인할 수 없습니다.")
            val checkout = SiteParser.checkout(SiteParser.document(get("/order.info.php", true).html), plan.date, plan.quantity, temporaryId)
            prepared[checkout] = PreparedCheckout(checkout.copy(fields = checkout.fields.mapValues { it.value.toList() }), plan.time)
            return checkout
        } catch (error: CancellationException) {
            throw error
        } catch (error: SiteException) {
            throw SiteException(error.code, error.message, attempted || error.submissionPossible)
        } catch (_: Exception) {
            throw SiteException("CHECKOUT_UNKNOWN", "신청정보를 확인하지 못했습니다. 주문내역 확인이 필요합니다.", attempted)
        }
    }

    @Synchronized
    override fun submit(checkout: CheckoutSnapshot): SubmitReceipt = submit(checkout) {}

    @Synchronized
    override fun submit(checkout: CheckoutSnapshot, beforeMutation: () -> Unit): SubmitReceipt {
        if (submitted.contains(checkout)) throw SiteException("ALREADY_SUBMITTED", "이미 제출을 시도한 신청정보입니다.", true)
        val saved = prepared[checkout] ?: throw SiteException("CHECKOUT_UNVERIFIED", "이 연결에서 검증한 신청정보가 아닙니다.")
        if (saved.snapshot != checkout) throw SiteException("CHECKOUT_UNVERIFIED", "이 연결에서 검증한 신청정보가 일치하지 않습니다.")
        if (checkout.date != LocalDate.now(clock)) throw SiteException("DATE_NOT_TODAY", "신청 날짜가 지나 제출을 중단했습니다.")
        if (checkout.quantity !in 1..5 || checkout.unitPrice <= 0 || checkout.unitPrice > Long.MAX_VALUE / checkout.quantity ||
            checkout.total != checkout.unitPrice * checkout.quantity || checkout.fields["uid[]"] != listOf(checkout.temporaryId) ||
            checkout.fields["qty[]"] != listOf(checkout.quantity.toString()) || checkout.fields["od_jikbun"] != listOf(checkout.accountId) ||
            checkout.fields["payroll"] != listOf("on")) throw SiteException("CHECKOUT_CONFLICT", "제출할 신청정보가 일치하지 않습니다.")
        val result = post("/togobox/order.reg.php", checkout.fields, true) {
            beforeMutation()
            purchaseGuard(checkout.date, saved.time)
            submitted.add(checkout)
            prepared.remove(checkout)
        }
        // A receipt is evidence only. The coordinator must read the same history row afterward.
        val alert = if (result.status == 200 && ScriptLiterals.completionAlert(result.html)) "주문이 완료 되었습니다." else null
        return SubmitReceipt(alert, result.status)
    }

    private data class Result(val status: Int, val html: String, val location: String?, val cached: Boolean,
        val requestStartedElapsed: Long, val responseFinishedElapsed: Long, val deviceWallMillis: Long)

    private fun get(path: String, mayChangeState: Boolean = false, requireFresh: Boolean = false,
        allowRedirects: Boolean = true, callDeadlineMillis: Long? = null): Result {
        var target = url(path)
        repeat(4) {
            val result = execute(Request.Builder().url(target).cacheControl(CacheControl.Builder().noCache().noStore().build()).get().build(),
                mayChangeState, callDeadlineMillis = callDeadlineMillis)
            if (requireFresh && result.cached) throw SiteException("CACHED_RESPONSE", "캐시된 사이트 응답으로 시각·메뉴를 확인할 수 없습니다.")
            if (result.status !in 300..399) { requireSuccess(result); return result }
            if (!allowRedirects) throw SiteException("TIME_UNAVAILABLE", "사이트 시각 조회가 이동 응답으로 중단되었습니다.")
            target = target.resolve(result.location ?: "")?.takeIf { sameOrigin(it) }
                ?: throw SiteException("REDIRECT_BLOCKED", "사이트 외부 이동이 차단되었습니다.")
        }
        throw SiteException("REDIRECT_BLOCKED", "사이트 이동이 반복되어 중단했습니다.")
    }

    private fun post(path: String, fields: Map<String, List<String>>, mayChangeState: Boolean, beforeRequest: () -> Unit = {}): Result {
        val body = FormBody.Builder().apply { fields.forEach { (key, values) -> values.forEach { add(key, it) } } }.build()
        return execute(Request.Builder().url(url(path)).post(body).build(), mayChangeState, beforeRequest)
    }

    private fun execute(request: Request, mayChangeState: Boolean, beforeRequest: () -> Unit = {},
        callDeadlineMillis: Long? = null): Result {
        beforeRequest()
        val started = elapsedMillis()
        try {
            val call = transport.newCall(request)
            if (callDeadlineMillis != null) call.timeout().timeout(callDeadlineMillis, TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                val body = response.body ?: throw SiteException("EMPTY_RESPONSE", "사이트 응답이 없습니다.", mayChangeState)
                val source = body.source()
                source.request(2_000_001)
                if (source.buffer.size > 2_000_000) throw SiteException("RESPONSE_TOO_LARGE", "사이트 응답 크기가 예상 범위를 벗어났습니다.", mayChangeState)
                val html = body.string()
                val finished = elapsedMillis()
                val completedWall = wallMillis()
                if (CloudbricClassifier.isBlocked(html)) {
                    throw SiteException(CloudbricClassifier.CODE, CloudbricClassifier.MESSAGE, mayChangeState)
                }
                val cached = response.cacheResponse != null || response.header("Age") != null ||
                    response.headers.values("X-Cache").any { Regex("(?i)\\b(HIT|STALE)\\b").containsMatchIn(it) } ||
                    response.header("CF-Cache-Status")?.uppercase() in setOf("HIT", "STALE", "UPDATING", "REVALIDATED") ||
                    response.headers.values("Warning").any { Regex("(?:^|,)\\s*11[01]\\b").containsMatchIn(it) }
                return Result(response.code, html, response.header("Location"), cached, started, finished, completedWall)
            }
        } catch (_: IOException) {
            throw SiteException(if (mayChangeState) "SUBMISSION_UNKNOWN" else "NETWORK", if (mayChangeState) "요청 결과가 불명확합니다. 주문내역 확인이 필요합니다." else "사이트 연결을 완료하지 못했습니다.", mayChangeState)
        }
    }

    private fun requireSuccess(result: Result) {
        if (result.status !in 200..299) throw SiteException("HTTP", "사이트 조회를 완료하지 못했습니다.")
    }

    private fun menuFreshness(menu: LoadedMenu) {
        val age = elapsedMillis() - menu.loadedElapsed
        if (age !in 0..10_000) throw SiteException("MENU_EXPIRED", "메뉴 조회 후 시간이 지나 다시 확인이 필요합니다.")
    }

    private fun purchaseGuard(date: LocalDate, time: LocalTime) {
        (clock as? ServerOrderClock)?.purchaseProblem(date, time)?.let { throw SiteException("SERVER_TIME_GATE", it) }
    }

    private fun json(result: Result, mayChangeState: Boolean): JsonObject {
        if (result.status !in 200..299) throw SiteException("HTTP", "임시 주문 응답을 확인할 수 없습니다.", mayChangeState)
        return try { JsonParser.parseString(result.html).asJsonObject } catch (_: RuntimeException) {
            throw SiteException("JSON_CONTRACT", "임시 주문 응답 구조를 확인할 수 없습니다.", mayChangeState)
        }
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString

    private fun url(path: String): HttpUrl = baseUrl.resolve(path)!!
    private fun sameOrigin(url: HttpUrl): Boolean = url.scheme == baseUrl.scheme && url.host == baseUrl.host && url.port == baseUrl.port && url.username.isEmpty() && url.password.isEmpty()
}
