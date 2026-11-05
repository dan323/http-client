package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.CookieJar
import com.dan323.httpclient.core.application.RedirectFollower
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestOptions
import com.dan323.httpclient.core.port.out.HttpExecutor
import java.net.http.HttpTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RedirectFollowerTest {
    private val sent = mutableListOf<HttpRequest>()
    private val script = ArrayDeque<HttpResponse>()
    private val executor = HttpExecutor { sent += it; script.removeFirst() }
    private val jar = CookieJar()
    private val follower = RedirectFollower(executor, jar)

    private fun redirect(status: Int, to: String?, vararg extra: Header, ms: Long = 1) =
        HttpResponse(status, listOfNotNull(to?.let { Header("Location", it) }) + extra, ByteArray(0), ms)

    private fun ok(ms: Long = 1) = HttpResponse(200, emptyList(), "done".toByteArray(), ms)
    private fun req(
        method: String = "GET",
        url: String = "http://a.test/start",
        headers: List<Header> = emptyList(),
        body: String? = null,
        options: RequestOptions = RequestOptions(),
    ) = HttpRequest(method, url, headers, body?.toByteArray(), options)

    @Test fun jarOffDelegatesToTheExecutorUntouched() {
        script += redirect(302, "/x")
        val r = req(options = RequestOptions(useCookieJar = false))
        assertEquals(302, follower.send(r).statusCode)
        assertEquals(listOf(r), sent)
    }

    @Test fun followsRelativeLocationAndSumsDurations() {
        script += redirect(302, "/next", ms = 10); script += ok(5)
        val response = follower.send(req())
        assertEquals(200, response.statusCode)
        assertEquals(15, response.durationMillis)
        assertEquals(listOf("http://a.test/start", "http://a.test/next"), sent.map { it.url })
        assertFalse(sent.any { it.options.followRedirects }, "the executor is told not to follow")
    }

    @Test fun noRedirectReturnsTheFirstResponse() {
        script += redirect(302, "/next")
        val response = follower.send(req(options = RequestOptions(followRedirects = false)))
        assertEquals(302, response.statusCode)
        assertEquals(1, sent.size)
    }

    @Test fun postBecomesGetOn301302303ButKeepsOn307308() {
        for ((status, method) in listOf(301 to "GET", 302 to "GET", 303 to "GET", 307 to "POST", 308 to "POST")) {
            sent.clear(); script.clear()
            script += redirect(status, "/n"); script += ok()
            follower.send(req("POST", headers = listOf(Header("Content-Type", "text/plain"), Header("X-A", "1")), body = "hi"))
            val second = sent[1]
            assertEquals(method, second.method, "status $status")
            if (method == "GET") {
                assertNull(second.body)
                assertEquals(listOf("X-A"), second.headers.map { it.name })
            } else {
                assertEquals("hi", second.body!!.toString(Charsets.UTF_8))
                assertEquals(2, second.headers.size)
            }
        }
    }

    @Test fun headStaysHeadOn303() {
        script += redirect(303, "/n"); script += ok()
        follower.send(req("HEAD"))
        assertEquals("HEAD", sent[1].method)
    }

    @Test fun credentialsAreDroppedOnlyWhenLeavingTheOrigin() {
        val creds = listOf(Header("Authorization", "Bearer t"), Header("Cookie", "a=b"), Header("Proxy-Authorization", "p"), Header("X-Keep", "1"))
        script += redirect(302, "/same"); script += redirect(302, "http://b.test/other"); script += ok()
        follower.send(req(headers = creds))
        assertEquals(4, sent[1].headers.size)
        assertEquals(listOf("X-Keep"), sent[2].headers.map { it.name })
    }

    @Test fun defaultPortCountsAsSameOrigin() {
        script += redirect(302, "http://a.test:80/p"); script += ok()
        follower.send(req(headers = listOf(Header("Authorization", "t"))))
        assertEquals(1, sent[1].headers.size)
    }

    @Test fun differentSchemeOrPortLeavesTheOrigin() {
        script += redirect(302, "https://a.test/p"); script += ok()
        follower.send(req(headers = listOf(Header("Authorization", "t"))))
        assertEquals(0, sent[1].headers.size)
        sent.clear(); script += redirect(302, "http://a.test:8080/p"); script += ok()
        follower.send(req(headers = listOf(Header("Authorization", "t"))))
        assertEquals(0, sent[1].headers.size)
    }

    @Test fun stopsOnMissingLocationOrNonHttpTargetOrNonRedirectStatus() {
        script += redirect(302, null)
        assertEquals(302, follower.send(req()).statusCode)
        script += redirect(302, "ftp://x/y")
        assertEquals(302, follower.send(req()).statusCode)
        script += redirect(304, "/n")
        assertEquals(304, follower.send(req()).statusCode)
        script += redirect(302, "http://[bad")
        assertEquals(302, follower.send(req()).statusCode)
    }

    @Test fun tooManyRedirectsFails() {
        repeat(20) { script += redirect(302, "/loop") }
        val e = assertFailsWith<IllegalStateException> { follower.send(req()) }
        assertEquals("Too many redirects (more than 10)", e.message)
        assertEquals(11, sent.size)
    }

    @Test fun exactlyTenRedirectsAreAllowed() {
        repeat(10) { script += redirect(302, "/n$it") }
        script += ok()
        assertEquals(200, follower.send(req()).statusCode)
    }

    @Test fun cookiesAreStoredAndRecomputedPerHop() {
        script += redirect(302, "/home", Header("Set-Cookie", "sid=1; Path=/")); script += ok()
        follower.send(req())
        assertNull(sent[0].headers.firstOrNull { it.name == "Cookie" })
        assertEquals("sid=1", sent[1].headers.single { it.name == "Cookie" }.value)
    }

    @Test fun timeoutIsOneDeadlineForTheChain() {
        script += redirect(302, "/n"); script += ok()
        follower.send(req(options = RequestOptions(timeoutMillis = 60_000)))
        val first = sent[0].options.timeoutMillis!!
        val second = sent[1].options.timeoutMillis!!
        assertEquals(true, second <= first && first <= 60_000, "remaining time shrinks: $first then $second")
    }

    @Test fun exhaustedDeadlineThrowsBeforeSending() {
        val slow = HttpExecutor { sent += it; Thread.sleep(30); redirect(302, "/n") }
        val f = RedirectFollower(slow, jar)
        assertFailsWith<HttpTimeoutException> { f.send(req(options = RequestOptions(timeoutMillis = 10))) }
        assertEquals(1, sent.size)
    }
}
