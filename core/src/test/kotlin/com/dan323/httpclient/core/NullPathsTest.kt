package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.AssertionEvaluator
import com.dan323.httpclient.core.application.CookieJar
import com.dan323.httpclient.core.application.RedirectFollower
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestOptionsParser
import com.dan323.httpclient.core.application.ResponseExtractor
import com.dan323.httpclient.core.application.ResponseLinks
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.application.RunReport
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.out.HttpExecutor
import com.dan323.httpclient.core.port.out.JsonExtractor
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The inputs where a value is absent (no header, no name, no host, an unmatched group) and the fallback answers: the places the Kotlin mutators (`?:`, `?.`, stdlib swaps) can break. */
class NullPathsTest {
    // AssertionEvaluator
    private val evaluator = AssertionEvaluator(ResponseExtractor(JsonExtractor { _, p -> mapOf("$.name" to "bob")[p] }))
    private fun response(body: String = "{}") = HttpResponse(200, listOf(Header("Content-Type", "application/json")), body.toByteArray(), 1)
    private fun eval(a: String) = evaluator.evaluate(a, response())

    @Test fun `an expected value that starts with a quote but does not end with it is taken literally`() {
        assertFalse(eval("body.$.name startsWith \"bo").passed)
        assertTrue(eval("body.$.name startsWith bo").passed)
    }

    @Test fun `a failed assertion says what was expected and what was found`() {
        val wrongStatus = eval("status == 201")
        assertEquals("expected status to equal 201, but was 200", wrongStatus.message)
        val missing = eval("body.$.none == 1")
        assertEquals("expected body.\$.none to equal 1, but was missing", missing.message)
        val exists = eval("body.$.none exists")
        assertEquals("expected body.\$.none to exist, but was missing", exists.message)
    }

    // ResponseExtractor
    @Test fun `a regex group that did not match gives nothing`() {
        val extractor = ResponseExtractor { _, _ -> null }
        assertNull(extractor.regex("b", "(a)?b"))
        assertEquals("a", extractor.regex("ab", "(a)?b"))
        assertEquals("b", extractor.regex("b", "b"))
    }

    // RunReport
    @Test fun `a report names a named request by its name and an unnamed one by method and url`() {
        val named = RunOutcome.Success(RequestTemplate("login", "GET", "http://x/a"), response(), emptyList())
        val unnamed = RunOutcome.Success(RequestTemplate(null, "POST", "http://x/b"), response(), emptyList())
        assertEquals(listOf("login", "POST http://x/b"), RunReport.from(listOf(named, unnamed)).entries.map { it.name })
    }

    // ResponseLinks
    @Test fun `the link key of an unnamed or blank request is its line`() {
        assertEquals("unnamed line 5", ResponseLinks.key(RequestTemplate(null, "GET", "http://x", startLine = 4)))
        assertEquals("unnamed line 1", ResponseLinks.key(RequestTemplate("  ", "GET", "http://x")))
        assertEquals("login", ResponseLinks.key(RequestTemplate("login", "GET", "http://x", startLine = 4)))
    }

    // RedirectFollower
    @Test fun `a redirect without a usable Location is returned as it is`() {
        for (location in listOf<String?>(null, "", "   ")) {
            val sent = mutableListOf<HttpRequest>()
            val reply = HttpResponse(302, listOfNotNull(location?.let { Header("Location", it) }), ByteArray(0), 1)
            val follower = RedirectFollower(HttpExecutor { sent += it; reply }, CookieJar())
            val result = follower.send(HttpRequest("GET", "http://a.test/start", emptyList(), null, com.dan323.httpclient.core.domain.RequestOptions()))
            assertEquals(302, result.statusCode, "location=$location")
            assertEquals(1, sent.size, "location=$location")
        }
    }

    // RequestOptionsParser
    private fun proxy(value: String) = RequestOptionsParser.parse(mapOf("proxy" to value)) { it }

    @Test fun `a proxy without a host or with a bad port is rejected with the reason`() {
        assertTrue(assertFailsWith<IllegalArgumentException> { proxy(":8080") }.message!!.contains("expected host:port"))
        val port = assertFailsWith<IllegalArgumentException> { proxy("h:70000") }
        assertEquals("Invalid @proxy value: 'h:70000' (port 70000 out of range)", port.message)
    }

    // BasicAuth
    @Test fun `an Authorization value that is not Basic with a credential is sent as it is`() {
        val runner = RequestRunner(HttpExecutor { error("not sent") }) { error("no files") }
        fun auth(value: String) = runner.resolve(RequestTemplate(null, "GET", "http://h", listOf(Header("Authorization", value))), emptyMap()).headers.single().value
        assertEquals("Basic", auth("Basic"))
        assertEquals("Basic ", auth("Basic "))
        assertEquals("Bearer abc", auth("Bearer abc"))
    }

    // CookieJar
    private var now = Instant.parse("2026-01-01T00:00:00Z")
    private val jar = CookieJar { now }
    private fun set(url: String, raw: String) = jar.store(url, listOf(Header("Set-Cookie", raw)))

    @Test fun `a url without a host stores and sends nothing`() {
        set("mailto:x", "a=1")
        assertNull(jar.headerFor("mailto:x"))
    }

    @Test fun `an unparseable Max-Age or Expires keeps the earlier valid one`() {
        set("http://h/", "a=1; Max-Age=100; Max-Age=soon")
        assertEquals("a=1", jar.headerFor("http://h/"))
        now = now.plus(Duration.ofSeconds(101))
        assertNull(jar.headerFor("http://h/"), "the valid Max-Age still applies")
        set("http://h/", "b=1; Expires=Fri, 01 Jan 2100 00:00:00 GMT; Expires=whenever")
        now = now.plus(Duration.ofDays(1))
        assertEquals("b=1", jar.headerFor("http://h/"))
    }

    @Test fun `an unparseable Expires after a valid one keeps the valid one`() {
        set("http://h/", "a=1; Expires=Thu, 01 Jan 2026 00:00:30 GMT; Expires=whenever")
        now = now.plus(Duration.ofSeconds(31))
        assertNull(jar.headerFor("http://h/"))
    }

    @Test fun `a cookie with an Expires date and no Max-Age expires then`() {
        set("http://h/", "a=1; Expires=Thu, 01 Jan 2026 00:00:30 GMT")
        assertEquals("a=1", jar.headerFor("http://h/"))
        now = now.plus(Duration.ofSeconds(31))
        assertNull(jar.headerFor("http://h/"))
    }

    @Test fun `a url with an empty path uses the root path`() {
        set("http://h", "a=1")
        assertEquals("a=1", jar.headerFor("http://h"))
        assertEquals("a=1", jar.headerFor("http://h/anything"))
    }

    @Test fun `a host the url parser cannot read is taken from the authority`() {
        set("http://my_service:8080/", "a=1")
        assertEquals("a=1", jar.headerFor("http://my_service:8080/"))
        assertEquals("a=1", jar.headerFor("http://my_service/"))
        set("http://user@my_other:81/", "b=2")
        assertEquals("b=2", jar.headerFor("http://my_other/"))
        set("http://[::1]:8080/", "c=3")
        assertEquals("c=3", jar.headerFor("http://[::1]:8080/"))
    }
}
