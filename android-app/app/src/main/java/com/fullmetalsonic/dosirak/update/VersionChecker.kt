package com.fullmetalsonic.dosirak.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit

data class UpdateStatus(
    val checking: Boolean = false,
    val latestVersion: String? = null,
    val available: Boolean = false,
    val message: String = "새 버전을 아직 확인하지 않았습니다.",
    val releaseUrl: String = VersionChecker.RELEASES_URL
)

internal data class SemanticVersion(
    val major: BigInteger,
    val minor: BigInteger,
    val patch: BigInteger,
    val prerelease: List<String> = emptyList()
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        for ((left, right) in listOf(major to other.major, minor to other.minor, patch to other.patch)) {
            val comparison = left.compareTo(right)
            if (comparison != 0) return comparison
        }
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1
        for ((left, right) in prerelease.zip(other.prerelease)) {
            val leftNumber = left.toBigIntegerOrNull()
            val rightNumber = right.toBigIntegerOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val format = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$")

        fun parse(value: String): SemanticVersion? {
            val match = format.matchEntire(value) ?: return null
            val identifiers = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (identifiers.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
            return SemanticVersion(match.groupValues[1].toBigInteger(), match.groupValues[2].toBigInteger(),
                match.groupValues[3].toBigInteger(), identifiers)
        }
    }
}

class VersionChecker(
    client: OkHttpClient = OkHttpClient(),
    private val apiUrl: HttpUrl = LATEST_API_URL.toHttpUrl(),
    testMode: Boolean = false
) {
    // Update requests must never reuse the meal site's cookies or credential interceptors.
    private val transport = client.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }.cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE).cache(null)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()

    init {
        val production = apiUrl.toString() == LATEST_API_URL
        val localTest = testMode && apiUrl.host in setOf("localhost", "127.0.0.1", "::1")
        require((production || localTest) && apiUrl.username.isEmpty() && apiUrl.password.isEmpty() &&
            apiUrl.query == null && apiUrl.fragment == null) { "허용되지 않은 업데이트 주소입니다." }
    }

    fun check(currentVersion: String): UpdateStatus {
        val current = SemanticVersion.parse(currentVersion)
            ?: return unavailable("현재 앱 버전을 비교할 수 없습니다. 배포 페이지를 확인해 주세요.")
        val request = Request.Builder().url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10")
            .header("User-Agent", "Poswel-Dosirak-Update-Checker").get().build()
        return try {
            transport.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> parseRelease(response.body?.string().orEmpty(), current)
                    404 -> unavailable("저장소가 비공개이거나 공개된 정식 배포가 없어 확인할 수 없습니다.")
                    401 -> unavailable("배포 정보를 확인할 수 없습니다. 배포 페이지를 확인해 주세요.")
                    403, 429 -> unavailable(if (response.code == 429 || response.header("X-RateLimit-Remaining") == "0" || response.header("Retry-After") != null)
                        "업데이트 확인 요청이 제한되었습니다. 잠시 후 다시 확인해 주세요."
                        else "배포 정보에 접근할 수 없습니다. 배포 페이지를 확인해 주세요.")
                    else -> unavailable("업데이트 정보를 확인하지 못했습니다. 잠시 후 다시 확인해 주세요.")
                }
            }
        } catch (_: IOException) {
            unavailable("네트워크 연결을 확인한 뒤 다시 시도해 주세요.")
        } catch (_: RuntimeException) {
            unavailable("배포 정보를 읽을 수 없습니다. 배포 페이지를 확인해 주세요.")
        }
    }

    private fun parseRelease(body: String, current: SemanticVersion): UpdateStatus {
        val root = JsonParser.parseString(body)
        if (!root.isJsonObject) return unavailable("배포 정보 형식이 올바르지 않습니다.")
        val release = root.asJsonObject
        val draft = release.boolean("draft")
        val prerelease = release.boolean("prerelease")
        if (draft == null || prerelease == null) return unavailable("배포 정보 형식이 올바르지 않습니다.")
        if (draft || prerelease) return unavailable("공개된 정식 배포를 확인할 수 없습니다.")
        val tag = release.string("tag_name") ?: return unavailable("배포 버전을 확인할 수 없습니다.")
        val latest = SemanticVersion.parse(tag) ?: return unavailable("배포 버전 형식이 올바르지 않습니다.")
        if (latest.prerelease.isNotEmpty()) return unavailable("공개된 정식 배포를 확인할 수 없습니다.")
        val url = release.string("html_url") ?: return unavailable("배포 페이지 주소를 확인할 수 없습니다.")
        if (!isAllowedReleaseUrl(url) || url.toHttpUrl().pathSegments.last() != tag || url == RELEASES_URL)
            return unavailable("배포 페이지 주소를 확인할 수 없습니다.")
        val available = latest > current
        return UpdateStatus(latestVersion = tag.removePrefix("v"), available = available,
            message = if (available) "새 버전이 있습니다. 배포 페이지에서 확인하세요." else "현재 앱은 최신 정식 버전 이상입니다.",
            releaseUrl = url)
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.boolean(key: String): Boolean? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    private fun unavailable(message: String) = UpdateStatus(message = message)

    companion object {
        const val RELEASES_URL = "https://github.com/fullmetalsonic/poswel-dosirak/releases"
        const val LATEST_API_URL = "https://api.github.com/repos/fullmetalsonic/poswel-dosirak/releases/latest"

        fun isAllowedReleaseUrl(value: String): Boolean {
            val url = value.toHttpUrlOrNull() ?: return false
            if (url.scheme != "https" || url.host != "github.com" || url.port != 443 ||
                url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return false
            if (value == RELEASES_URL) return true
            return url.pathSegments.size == 5 && url.pathSegments.take(4) ==
                listOf("fullmetalsonic", "poswel-dosirak", "releases", "tag") &&
                SemanticVersion.parse(url.pathSegments.last()) != null
        }
    }
}
