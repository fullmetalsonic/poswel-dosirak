package com.fullmetalsonic.dosirak.update

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VersionCheckerTest {
    private lateinit var server: MockWebServer
    private lateinit var checker: VersionChecker

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        checker = VersionChecker(apiUrl = server.url("/repos/fullmetalsonic/poswel-dosirak/releases/latest"), testMode = true)
    }

    @After fun tearDown() { server.shutdown() }

    private fun release(tag: String = "v0.1.6", draft: Boolean = false, prerelease: Boolean = false,
        url: String = "${VersionChecker.RELEASES_URL}/tag/$tag") =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"html_url":"$url","assets":[]}"""

    private fun enqueue(body: String = release(), status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    }

    @Test fun newerStableReleaseShowsVersionAndReleasePageWithoutApkClaim() {
        enqueue()
        val result = checker.check("0.1.5")
        assertTrue(result.available)
        assertEquals("0.1.6", result.latestVersion)
        assertEquals("${VersionChecker.RELEASES_URL}/tag/v0.1.6", result.releaseUrl)
        assertTrue(result.message.contains("배포 페이지"))
        assertFalse(result.message.contains("설치"))
        assertFalse(result.checking)
    }

    @Test fun stableReleaseUpdatesMatchingInstalledTestVersion() {
        enqueue()
        assertTrue(checker.check("0.1.6-test").available)
    }

    @Test fun equalOrOlderReleaseDoesNotOfferDowngrade() {
        for (current in listOf("0.1.6", "0.1.7", "0.1.7-test")) {
            enqueue()
            val result = checker.check(current)
            assertFalse(result.available)
            assertEquals("0.1.6", result.latestVersion)
        }
    }

    @Test fun draftsPrereleasesAndPrereleaseTagsAreExcluded() {
        for (body in listOf(release(draft = true), release(prerelease = true), release(tag = "v0.1.7-rc.1"))) {
            enqueue(body)
            val result = checker.check("0.1.5")
            assertFalse(result.available)
            assertNull(result.latestVersion)
            assertEquals(VersionChecker.RELEASES_URL, result.releaseUrl)
        }
    }

    @Test fun invalidJsonTagsAndMissingOrWrongTypeFlagsCannotAdvertiseUpdate() {
        for (body in listOf("not-json", "[]", "{}", release(tag = "latest"),
            release().replace("\"draft\":false", "\"draft\":\"false\""),
            release().replace("\"prerelease\":false,", ""))) {
            enqueue(body)
            val result = checker.check("0.1.5")
            assertFalse(result.available)
            assertNull(result.latestVersion)
            assertFalse(result.checking)
        }
    }

    @Test fun privateRepositoryOrMissingReleaseHasExplicit404Message() {
        enqueue("{}", 404)
        val result = checker.check("0.1.6-test")
        assertFalse(result.available)
        assertTrue(result.message.contains("비공개"))
        assertTrue(result.message.contains("정식 배포"))
        assertEquals(VersionChecker.RELEASES_URL, result.releaseUrl)
    }

    @Test fun authorizationAndServerErrorsReturnNonBlockingResults() {
        for (code in listOf(401, 403, 500)) {
            enqueue("{}", code)
            val result = checker.check("0.1.5")
            assertFalse(result.available)
            assertFalse(result.checking)
            assertNull(result.latestVersion)
        }
    }

    @Test fun rateLimitIsDistinguishedFor403And429() {
        for (code in listOf(403, 429)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("X-RateLimit-Remaining", "0"))
            assertTrue(checker.check("0.1.5").message.contains("제한"))
        }
    }

    @Test fun connectionFailureDoesNotThrowOrRetry() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val result = checker.check("0.1.5")
        assertFalse(result.available)
        assertTrue(result.message.contains("네트워크"))
        assertEquals(1, server.requestCount)
    }

    @Test fun unknownInstalledVersionCannotBeComparedAndMakesNoRequest() {
        assertFalse(checker.check("unknown").available)
        assertTrue(checker.check("").message.contains("현재 앱 버전"))
        assertEquals(0, server.requestCount)
    }

    @Test fun foreignUnsafeOrMismatchedReleaseLinksAreRejected() {
        for (url in listOf("https://example.org/update.apk", "https://github.com/other/repo/releases/tag/v0.1.6",
            "http://github.com/fullmetalsonic/poswel-dosirak/releases/tag/v0.1.6",
            "${VersionChecker.RELEASES_URL}/tag/v0.1.6?token=anything",
            "${VersionChecker.RELEASES_URL}/tag/v0.1.7", VersionChecker.RELEASES_URL)) {
            enqueue(release(url = url))
            val result = checker.check("0.1.5")
            assertFalse(result.available)
            assertEquals(VersionChecker.RELEASES_URL, result.releaseUrl)
        }
    }

    @Test fun releaseUrlAllowlistRejectsForeignHostsCredentialsAndDownloads() {
        assertTrue(VersionChecker.isAllowedReleaseUrl(VersionChecker.RELEASES_URL))
        assertTrue(VersionChecker.isAllowedReleaseUrl("${VersionChecker.RELEASES_URL}/tag/v0.1.6"))
        for (url in listOf("https://github.com.evil.example/fullmetalsonic/poswel-dosirak/releases/tag/v0.1.6",
            "https://user:password@github.com/fullmetalsonic/poswel-dosirak/releases/tag/v0.1.6",
            "${VersionChecker.RELEASES_URL}/download/v0.1.6/app.apk", "${VersionChecker.RELEASES_URL}/tag/v0.1.6#apk",
            "${VersionChecker.RELEASES_URL}/tag/latest", "${VersionChecker.RELEASES_URL}/tag/v0.1.6/extra")) {
            assertFalse(url, VersionChecker.isAllowedReleaseUrl(url))
        }
    }

    @Test fun onlyReadOnlyGitHubRequestIsSentWithoutInjectedSiteCredentials() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "secret-site-token").build())
        }.cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("site-session").value("private")
                .domain(url.host).build())
        }).build()
        val isolated = VersionChecker(client, server.url("/repos/fullmetalsonic/poswel-dosirak/releases/latest"), true)
        enqueue()
        isolated.check("0.1.5")
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/repos/fullmetalsonic/poswel-dosirak/releases/latest", request.path)
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals(1, server.requestCount)
    }

    @Test fun redirectsAreNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/foreign")))
        assertFalse(checker.check("0.1.5").available)
        assertEquals(1, server.requestCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun injectedLoopbackEndpointRequiresExplicitTestMode() {
        VersionChecker(apiUrl = server.url("/"))
    }
}
