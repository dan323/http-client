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
}
