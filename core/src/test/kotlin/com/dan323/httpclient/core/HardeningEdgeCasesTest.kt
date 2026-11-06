package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.ResponseExtractor
import com.dan323.httpclient.core.application.RunOutcome
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

class HardeningEdgeCasesTest {
    private fun session(executor: HttpExecutor) =
        RequestSession(RequestRunner(executor) { error("no files") }, executor) { _, _ -> null }

    private fun failure(t: RequestTemplate, vars: Map<String, String>, exec: HttpExecutor): String =
        (session(exec).runAll(listOf(t), vars).single() as RunOutcome.Failure).message

    private val echo = HttpExecutor { req -> error("failed ${req.url} ${req.headers.joinToString { it.value }} ${req.body?.toString(Charsets.UTF_8)}") }

    // ---- scrubbing ----

    @Test fun shortAndEmptyValuesAreNotOverScrubbed() {
        val msg = failure(RequestTemplate(null, "GET", "http://h/a?x={{a}}&y={{e}}&z={{b}}"), mapOf("a" to "1", "e" to "", "b" to "xy"), echo)
        assertTrue("http://h/a?x=1&y=&z=xy" in msg, msg)
        assertFalse("{{" in msg, msg)
    }

    @Test fun valueOfExactMinLengthIsScrubbed() {
        val msg = failure(RequestTemplate(null, "GET", "http://h/?k={{k}}"), mapOf("k" to "q9z"), echo)
        assertFalse("q9z" in msg, msg)
        assertTrue("{{k}}" in msg, msg)
    }

    @Test fun regexSpecialValuesAreScrubbedLiterally() {
        val v = "a.b*c+(d)[e]\$^|\\"
        val msg = failure(RequestTemplate(null, "POST", "http://h/", emptyList(), Body.Text("v={{v}} other=aXbbc")), mapOf("v" to v), echo)
        assertFalse(v in msg, msg)
        assertTrue("{{v}}" in msg, msg)
        assertTrue("other=aXbbc" in msg, "unrelated text must survive: $msg")
    }

    @Test fun urlEncodedFormsAreScrubbed() {
        val v = "p@ss word&x=1"
        val msg = failure(RequestTemplate(null, "GET", "http://h/"), mapOf("v" to v), HttpExecutor { error("bad http://h/?t=p%40ss+word%26x%3D1 and p%40ss%20word%26x%3D1") })
        // not substituted into the request, so nothing is recorded: message untouched
        assertTrue("p%40ss" in msg)
        val msg2 = failure(RequestTemplate(null, "GET", "http://h/?t={{v}}"), mapOf("v" to v), HttpExecutor { error("bad http://h/?t=p%40ss+word%26x%3D1 and p%40ss%20word%26x%3D1") })
        assertFalse("p%40ss" in msg2, msg2)
    }

    @Test fun uriCreateWithTokenInQueryDoesNotLeak() {
        val tok = "ghp_ABCdef123456"
        val msg = failure(RequestTemplate(null, "GET", "http://h/a b?token={{t}}"), mapOf("t" to tok), HttpExecutor { java.net.URI.create(it.url); error("x") })
        assertFalse(tok in msg, msg)
    }

    @Test fun overlappingValuesAreHiddenAsWhole() {
        val msg = failure(RequestTemplate(null, "GET", "http://h/?a={{a}}&b={{b}}"), mapOf("a" to "secret", "b" to "secret-long"), echo)
        assertFalse("secret" in msg, msg)
    }

    @Test fun replacementTextIsNotRescrubbed() {
        // value "tok" must not be matched inside the "{{name}}" placeholder produced for another value
        val msg = failure(RequestTemplate(null, "GET", "http://h/?a={{tok}}&b={{other}}"), mapOf("tok" to "hunter2", "other" to "tok"), echo)
        assertFalse("hunter2" in msg, msg)
        assertFalse("{{{{" in msg, msg)
        assertTrue("{{tok}}" in msg, msg)
    }

    @Test fun substitutionsFromEarlierRequestDoNotAffectNextFailure() {
        val exec = HttpExecutor { req -> if (req.url.contains("one")) error("ok-ish") else error("fail ${req.url}") }
        val outs = session(exec).runAll(listOf(
            RequestTemplate(null, "GET", "http://h/one/{{a}}"),
            RequestTemplate(null, "GET", "http://h/two/shared-text"),
        ), mapOf("a" to "shared-text"))
        assertTrue("shared-text" in (outs[1] as RunOutcome.Failure).message)
    }

    @Test fun failureWithoutMessageStillReported() {
        val msg = failure(RequestTemplate(null, "GET", "http://h/"), emptyMap(), HttpExecutor { throw IllegalStateException() })
        assertEquals("IllegalStateException", msg)
    }

    // ---- charset ----

    private fun resp(ct: String?, bytes: ByteArray) =
        HttpResponse(200, listOfNotNull(ct?.let { Header("Content-Type", it) }), bytes, 1)
    private val ex = ResponseExtractor { _, _ -> null }

    @Test fun charsetVariants() {
        val s = "héllo €"
        assertEquals(s, ex.extract(resp("text/plain;charset=UTF-16", s.toByteArray(Charsets.UTF_16)), "body.*"))
        assertEquals(s, ex.extract(resp("text/plain; CHARSET=utf-8", s.toByteArray()), "body.*"))
        assertEquals("é", ex.extract(resp("text/plain; charset='windows-1252'", byteArrayOf(0xE9.toByte())), "body.*"))
        assertEquals("é", ex.extract(resp("text/plain; format=x; charset=latin1", byteArrayOf(0xE9.toByte())), "body.*"))
    }

    @Test fun malformedCharsetFallsBackToUtf8() {
        for (ct in listOf("text/plain; charset=", "text/plain; charset=\"\"", "text/plain; charset=bad name!", "text/plain; charset", "charset=iso-8859-1")) {
            assertEquals("é", ex.extract(resp(ct, "é".toByteArray()), "body.*"), ct)
        }
    }

    @Test fun charsetAppliesToJsonXpathAndRegexBodies() {
        val xml = "<r><a>café</a></r>".toByteArray(Charsets.ISO_8859_1)
        val r = resp("application/xml; charset=ISO-8859-1", xml)
        assertEquals("café", ex.extract(r, "body.//a"))
        assertEquals("café", ex.extract(r, "body.regex:(caf.)"))
    }

    // ---- repeated headers ----

    private fun hdrs(vararg h: Pair<String, String>) = HttpResponse(200, h.map { Header(it.first, it.second) }, ByteArray(0), 1)

    @Test fun indexedHeaderEdgeCases() {
        val r = hdrs("Set-Cookie" to "a=1", "X-Other" to "o", "Set-Cookie" to "b=2", "set-cookie" to "c=3")
        assertEquals("a=1", ex.extract(r, "headers.Set-Cookie[0]"))
        assertEquals("c=3", ex.extract(r, "headers.Set-Cookie[2]"))
        assertEquals("b=2", ex.extract(r, "headers.set-cookie[1]"))
        assertEquals("a=1\nb=2\nc=3", ex.extract(r, "headers.Set-Cookie[*]"))
        assertEquals("o", ex.extract(r, "headers.X-Other[*]"))
        assertNull(ex.extract(r, "headers.Set-Cookie[3]"))
        assertNull(ex.extract(r, "headers.Set-Cookie[99999999999]"))
        assertNull(ex.extract(r, "headers.Set-Cookie[-1]"))
        assertNull(ex.extract(r, "headers.Set-Cookie[]"))
        assertNull(ex.extract(r, "headers.Missing[0]"))
    }

    @Test fun literalBracketHeaderNameStillWorks() {
        val r = hdrs("X-Weird[5]" to "v")
        assertEquals("v", ex.extract(r, "headers.X-Weird[5]")) // falls through? documented as index syntax
    }

    @Test fun emptyHeaderValueIsKept() {
        val r = hdrs("Set-Cookie" to "", "Set-Cookie" to "b=2")
        assertEquals("", ex.extract(r, "headers.Set-Cookie[0]"))
        assertEquals("\nb=2", ex.extract(r, "headers.Set-Cookie[*]"))
    }

    // ---- XPath ----

    @Test fun xpathRejectsDoctypeVariants() {
        val internal = "<!DOCTYPE r [<!ENTITY x \"boom\">]><r>&x;</r>"
        assertNull(ex.xpath(internal, "/r"))
        val billion = "<!DOCTYPE l [<!ENTITY a \"aaaa\"><!ENTITY b \"&a;&a;&a;&a;\">]><l>&b;&b;</l>"
        assertNull(ex.xpath(billion, "/l"))
        assertNull(ex.xpath("<!DOCTYPE r SYSTEM \"http://127.0.0.1:9/x.dtd\"><r>1</r>", "/r"))
    }

    @Test fun xpathMalformedInputsReturnNull() {
        assertNull(ex.xpath("", "/r"))
        assertNull(ex.xpath("<r>", "/r"))
        assertNull(ex.xpath("<r/>", "//[["))
        assertNull(ex.xpath("<r/>", "/nothing"))
    }

    @Test fun xpathScalarAndCount() {
        assertEquals("2.0", ex.xpath("<r><a/><a/></r>", "count(//a)")?.let { if (it == "2") "2.0" else it })
        assertEquals("true", ex.xpath("<r><a/></r>", "boolean(//a)"))
    }

    @Test fun xpathTimeoutReturnsNullQuicklyAndRepeatedly() {
        val xml = "<r>" + "<a>x</a>".repeat(800) + "</r>"
        val expr = "count(//a[count(//a[count(//a[count(//a[count(//a[count(//a[count(//a)>0])>0])>0])>0])>0])>0])"
        repeat(2) {
            val start = System.nanoTime()
            assertNull(ex.xpath(xml, expr))
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue(ms < ResponseExtractor.XPATH_TIMEOUT_MILLIS + 2_000, "took $ms ms")
        }
        assertEquals("one", ex.xpath("<r><a>one</a></r>", "//a")) // still usable afterwards
    }

    @Test fun xpathViaBodyExpression() {
        val r = resp("application/xml", "<r><a id='1'>one</a></r>".toByteArray())
        assertEquals("one", ex.extract(r, "body.//a[@id='1']"))
        assertEquals("1", ex.extract(r, "body.xpath:count(//a)"))
    }
}
