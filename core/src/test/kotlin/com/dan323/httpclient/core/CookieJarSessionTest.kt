package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.out.HttpExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CookieJarSessionTest {
    private val sent = mutableListOf<HttpRequest>()
    private var setCookies = listOf("sid=abc; Path=/")
    private val executor = HttpExecutor { req ->
        sent += req
        HttpResponse(200, setCookies.map { Header("Set-Cookie", it) }, ByteArray(0), 1)
    }

    private fun newSession() = RequestSession(RequestRunner(executor) { error("no files") }, executor) { _, _ -> null }
    private fun get(url: String, vararg settings: Pair<String, String>) =
        RequestTemplate(null, "GET", url, settings = mapOf(*settings))

    private fun cookieOfLast() = sent.last().headers.firstOrNull { it.name == "Cookie" }?.value

    @Test fun cookieFromEarlierResponseIsSentLater() {
        val s = newSession()
        s.run(get("http://h/login"), emptyMap())
        assertEquals(null, cookieOfLast())
        s.run(get("http://h/profile"), emptyMap())
        assertEquals("sid=abc", cookieOfLast())
    }

    @Test fun otherHostAndPathDoNotMatch() {
        setCookies = listOf("sid=abc; Path=/api")
        val s = newSession()
        s.run(get("http://h/login"), emptyMap())
        s.run(get("http://other/api"), emptyMap())
        assertEquals(null, cookieOfLast())
        s.run(get("http://h/apix"), emptyMap())
        assertEquals(null, cookieOfLast())
        s.run(get("http://h/api/x"), emptyMap())
        assertEquals("sid=abc", cookieOfLast())
    }

    @Test fun noCookieJarNeitherSendsNorStores() {
        val s = newSession()
        s.run(get("http://h/login", "no-cookie-jar" to ""), emptyMap())
        s.run(get("http://h/a"), emptyMap())
        assertEquals(null, cookieOfLast()) // not stored by the opted-out login
        s.run(get("http://h/login"), emptyMap())
        s.run(get("http://h/b", "No-Cookie-Jar" to "true"), emptyMap())
        assertEquals(null, cookieOfLast()) // not sent
        s.run(get("http://h/c", "no-cookie-jar" to "false"), emptyMap())
        assertEquals("sid=abc", cookieOfLast())
        assertFailsWith<IllegalArgumentException> { s.run(get("http://h/d", "no-cookie-jar" to "maybe"), emptyMap()) }
    }

    @Test fun sessionsDoNotShareCookies() {
        newSession().run(get("http://h/login"), emptyMap())
        newSession().run(get("http://h/x"), emptyMap())
        assertEquals(null, cookieOfLast())
    }

    @Test fun explicitCookieHeaderWinsAndExpiredCookiesAreDropped() {
        val s = newSession()
        s.run(get("http://h/login"), emptyMap())
        s.run(RequestTemplate(null, "GET", "http://h/x", listOf(Header("cookie", "mine=1"))), emptyMap())
        assertEquals(listOf("mine=1"), sent.last().headers.map { it.value })
        setCookies = listOf("sid=; Max-Age=0; Path=/")
        s.run(get("http://h/logout"), emptyMap())
        s.run(get("http://h/y"), emptyMap())
        assertEquals(null, cookieOfLast())
    }

    @Test fun foreignDomainAndSecureRules() {
        setCookies = listOf("a=1; Domain=evil.com", "b=2; Secure", "c=3; Domain=h.com")
        val s = newSession()
        s.run(get("http://www.h.com/login"), emptyMap())
        s.run(get("http://api.h.com/x"), emptyMap())
        assertEquals("c=3", cookieOfLast())
    }

    // ---- redirects (hop by hop, handled by the session) ----

    private fun redirecting(routes: Map<String, HttpResponse>) = HttpExecutor { req ->
        sent += req
        routes[req.url] ?: HttpResponse(200, emptyList(), ByteArray(0), 1)
    }

    private fun redirect(code: Int, to: String, vararg cookies: String) =
        HttpResponse(code, listOf(Header("Location", to)) + cookies.map { Header("Set-Cookie", it) }, ByteArray(0), 5)

    private fun sessionOver(e: HttpExecutor) = RequestSession(RequestRunner(e) { error("no files") }, e) { _, _ -> null }

    @Test fun cookieSetOnRedirectResponseIsStoredAndSentOnNextHop() {
        val s = sessionOver(redirecting(mapOf("http://h/login" to redirect(302, "/home", "sid=1; Path=/"))))
        val r = s.run(RequestTemplate(null, "POST", "http://h/login", body = com.dan323.httpclient.core.domain.Body.Text("u=1")), emptyMap())
        assertEquals(200, r.statusCode)
        assertEquals(listOf("http://h/login", "http://h/home"), sent.map { it.url })
        assertEquals(listOf("POST", "GET"), sent.map { it.method })
        assertEquals(null, sent[1].body)
        assertEquals("sid=1", sent[1].headers.first { it.name == "Cookie" }.value)
        assertEquals(6, r.durationMillis) // 5 + 1
        assertEquals(false, sent.any { it.options.followRedirects })
        s.run(get("http://h/later"), emptyMap())
        assertEquals("sid=1", cookieOfLast())
    }

    @Test fun crossHostRedirectGetsOnlyTheTargetsCookiesAndDropsExplicitCredentials() {
        val s = sessionOver(redirecting(mapOf(
            "http://a/seed" to HttpResponse(200, listOf(Header("Set-Cookie", "sid=a; Path=/")), ByteArray(0), 1),
            "http://a/x" to redirect(302, "http://b/y"),
        )))
        s.run(get("http://a/seed"), emptyMap())
        sent.clear()
        s.run(get("http://a/x"), emptyMap())
        assertEquals(listOf("sid=a", null), sent.map { r -> r.headers.firstOrNull { it.name == "Cookie" }?.value })
        sent.clear()
        s.run(RequestTemplate(null, "GET", "http://a/x", listOf(Header("Authorization", "Bearer t"), Header("X-Keep", "1"))), emptyMap())
        assertEquals(listOf("Authorization", "X-Keep", "Cookie"), sent[0].headers.map { it.name })
        assertEquals(listOf("X-Keep"), sent[1].headers.map { it.name })
        sent.clear()
        s.run(RequestTemplate(null, "GET", "http://a/x", listOf(Header("Cookie", "mine=1"))), emptyMap())
        assertEquals(listOf("mine=1"), sent[0].headers.map { it.value })
        assertEquals(emptyList(), sent[1].headers)
    }

    @Test fun noRedirectReturnsFirstResponseButStoresItsCookies() {
        val s = sessionOver(redirecting(mapOf("http://h/login" to redirect(302, "/home", "sid=1"))))
        val r = s.run(get("http://h/login", "no-redirect" to ""), emptyMap())
        assertEquals(302, r.statusCode)
        assertEquals(1, sent.size)
        s.run(get("http://h/x"), emptyMap())
        assertEquals("sid=1", cookieOfLast())
    }

    @Test fun redirectLoopStopsAtTheLimit() {
        val s = sessionOver(HttpExecutor { sent += it; redirect(302, "/again") })
        val e = assertFailsWith<IllegalStateException> { s.run(get("http://h/start"), emptyMap()) }
        assertEquals(true, "Too many redirects" in e.message!!)
        assertEquals(11, sent.size)
    }

    @Test fun status307KeepsMethodAndBodyAndRelativeLocationsResolve() {
        val s = sessionOver(redirecting(mapOf("http://h/a/b" to redirect(307, "c"))))
        s.run(RequestTemplate(null, "PUT", "http://h/a/b", body = com.dan323.httpclient.core.domain.Body.Text("x")), emptyMap())
        assertEquals(listOf("PUT", "PUT"), sent.map { it.method })
        assertEquals("http://h/a/c", sent[1].url)
        assertEquals("x", String(sent[1].body!!))
    }

    @Test fun optedOutRequestIsFollowedByTheSessionWithoutTheJar() {
        val s = sessionOver(redirecting(emptyMap()))
        s.run(get("http://h/x", "no-cookie-jar" to ""), emptyMap())
        assertEquals(false, sent.single().options.followRedirects) // redirects are never left to the executor
    }

    @Test fun cancellationStopsRunAllInsteadOfContinuing() {
        val calls = mutableListOf<String>()
        val ex = HttpExecutor { req ->
            calls += req.url
            throw java.util.concurrent.CancellationException("cancelled")
        }
        val s = RequestSession(RequestRunner(ex) { error("no files") }, ex) { _, _ -> null }
        assertFailsWith<java.util.concurrent.CancellationException> {
            s.runAll(listOf(get("http://h/a"), get("http://h/b")), emptyMap())
        }
        assertEquals(listOf("http://h/a"), calls)
    }

    @Test fun timeoutIsOneDeadlineAcrossRedirectHops() {
        val timeouts = mutableListOf<Long?>()
        val ex = HttpExecutor { req ->
            timeouts += req.options.timeoutMillis
            Thread.sleep(150)
            if (req.url.endsWith("/a")) HttpResponse(302, listOf(Header("Location", "/b")), ByteArray(0), 150)
            else HttpResponse(200, emptyList(), ByteArray(0), 150)
        }
        val s = RequestSession(RequestRunner(ex) { error("no files") }, ex) { _, _ -> null }
        s.run(get("http://h/a", "timeout" to "10"), emptyMap())
        assertEquals(2, timeouts.size)
        assertEquals(true, timeouts[1]!! < timeouts[0]!! - 100, "second hop gets the remaining time: $timeouts")
    }
}
