package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.JUnitXmlReport
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.ResponseExtractor
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.application.RunReport
import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.out.HttpExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedactionHardeningTest {
    private val secret = "s3cr3t-T0KEN value"

    private fun session(executor: HttpExecutor) =
        RequestSession(RequestRunner(executor) { error("no files") }, executor) { _, _ -> null }

    private fun failureOf(t: RequestTemplate, executor: HttpExecutor): RunOutcome.Failure =
        session(executor).runAll(listOf(t), mapOf("tok" to secret)).single() as RunOutcome.Failure

    @Test fun badUriDoesNotLeakUrlValue() {
        // a space makes URI.create throw with the whole resolved URL in the message
        val failure = failureOf(RequestTemplate(null, "GET", "http://h/a?t={{tok}}"), HttpExecutor { req ->
            java.net.URI.create(req.url); error("unreachable")
        })
        assertFalse(secret in failure.message, failure.message)
        assertFalse("s3cr3t" in failure.message, failure.message)
        assertTrue("{{tok}}" in failure.message, failure.message)
        assertTrue("Illegal character" in failure.message, "diagnostic kept: ${failure.message}")
    }

    @Test fun executorErrorDoesNotLeakHeaderOrBodyValues() {
        val t = RequestTemplate(null, "POST", "http://h", listOf(Header("Authorization", "Bearer {{tok}}")), Body.Text("pw={{tok}}"))
        val failure = failureOf(t, HttpExecutor { req ->
            error("bad ${req.headers.single().value} / ${req.body!!.toString(Charsets.UTF_8)} / " +
                java.net.URLEncoder.encode(secret, Charsets.UTF_8))
        })
        assertFalse(secret in failure.message)
        assertFalse(java.net.URLEncoder.encode(secret, Charsets.UTF_8) in failure.message)
        assertTrue("{{tok}}" in failure.message)
    }

    @Test fun reportsDoNotContainSecret() {
        val outcome = failureOf(RequestTemplate("r", "GET", "http://h/?t={{tok}}"), HttpExecutor { req -> error("failed ${req.url}") })
        val xml = JUnitXmlReport.render(RunReport.from(listOf(outcome)))
        assertFalse(secret in xml)
        assertFalse("s3cr3t" in xml)
        assertTrue("{{tok}}" in xml)
    }

    @Test fun chainedResponseValueIsScrubbed() {
        val exec = HttpExecutor { req ->
            if (req.url.endsWith("/login")) HttpResponse(200, listOf(Header("X-Tok", "chained-secret-99")), ByteArray(0), 1)
            else error("boom ${req.url}")
        }
        val outcomes = session(exec).runAll(listOf(
            RequestTemplate("login", "GET", "http://h/login"),
            RequestTemplate(null, "GET", "http://h/?t={{login.response.headers.X-Tok}}"),
        ), emptyMap())
        val message = (outcomes[1] as RunOutcome.Failure).message
        assertFalse("chained-secret-99" in message, message)
    }

    // ---- extraction ----

    private val extractor = ResponseExtractor { _, _ -> null }

    @Test fun bodyHonoursContentTypeCharset() {
        val bytes = "café".toByteArray(Charsets.ISO_8859_1)
        val r = HttpResponse(200, listOf(Header("Content-Type", "text/plain; charset=\"ISO-8859-1\"")), bytes, 1)
        assertEquals("café", extractor.extract(r, "body.*"))
        val unknown = HttpResponse(200, listOf(Header("Content-Type", "text/plain; charset=nope")), "café".toByteArray(), 1)
        assertEquals("café", extractor.extract(unknown, "body.*"))
        assertEquals("café", extractor.extract(HttpResponse(200, emptyList(), "café".toByteArray(), 1), "body.*"))
    }

    @Test fun repeatedHeaders() {
        val r = HttpResponse(200, listOf(Header("Set-Cookie", "a=1; Expires=Wed, 01 Jan 2031 00:00:00 GMT"), Header("set-cookie", "b=2")), ByteArray(0), 1)
        assertEquals("a=1; Expires=Wed, 01 Jan 2031 00:00:00 GMT", extractor.extract(r, "headers.Set-Cookie"))
        assertEquals("b=2", extractor.extract(r, "headers.SET-COOKIE[1]"))
        assertNull(extractor.extract(r, "headers.Set-Cookie[2]"))
        assertEquals("a=1; Expires=Wed, 01 Jan 2031 00:00:00 GMT\nb=2", extractor.extract(r, "headers.Set-Cookie[*]"))
        assertNull(extractor.extract(r, "headers.Nope[*]"))
    }

    @Test fun xpathStillWorksAndRejectsDoctypeAndExtensions() {
        assertEquals("one", extractor.xpath("<r><a>one</a></r>", "//a"))
        assertNull(extractor.xpath("<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>", "/r"))
        assertNull(extractor.xpath("<r/>", "java:java.lang.Runtime.getRuntime()"))
    }

    @Test fun xpathIsTimeBounded() {
        val xml = "<r>" + "<a>x</a>".repeat(400) + "</r>"
        val expr = "count(//a[count(//a[count(//a[count(//a[count(//a[count(//a)>0])>0])>0])>0])>0])"
        val start = System.nanoTime()
        extractor.xpath(xml, expr)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < ResponseExtractor.XPATH_TIMEOUT_MILLIS + 3_000, "took $ms ms")
    }
}
