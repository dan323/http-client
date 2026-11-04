package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.AssertionResult
import com.dan323.httpclient.core.application.JUnitXmlReport
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.application.RunReport
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunReportTest {
    private fun req(name: String?, url: String = "http://x/a") = RequestTemplate(name, "GET", url)
    private fun ok(name: String?, ms: Long, vararg a: AssertionResult) =
        RunOutcome.Success(req(name), HttpResponse(200, emptyList(), ByteArray(0), ms), a.toList())
    private fun pass(s: String) = AssertionResult(s, true, "200", "200", "ok")
    private fun fail(s: String) = AssertionResult(s, false, "200", "500", "expected 200 but was 500")

    private val mixed = listOf(
        ok("a", 120, pass("status == 200")),
        ok(null, 80, pass("status == 200"), fail("status == 200")),
        RunOutcome.Failure(req("c"), "connection refused", assertionsNotRun = 2),
    )

    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))

    @Test fun buildsEntriesAndTotals() {
        val r = RunReport.from(mixed)
        assertEquals(3, r.requests)
        assertEquals(1, r.passedRequests)
        assertEquals(2, r.failedRequests)
        assertEquals(2, r.assertionsPassed)
        assertEquals(1, r.assertionsFailed)
        assertEquals(2, r.assertionsNotRun)
        assertEquals(200, r.totalDurationMillis)
        assertFalse(r.passed)
        assertEquals("GET http://x/a", r.entries[1].name)
        assertEquals(200, r.entries[0].status)
        assertNull(r.entries[2].status)
        assertEquals("connection refused", r.entries[2].error)
    }

    @Test fun allPassingAndEmpty() {
        assertTrue(RunReport.from(listOf(ok("a", 1, pass("s")))).passed)
        assertTrue(RunReport.from(emptyList()).passed)
    }

    @Test fun junitStructureParsesBack() {
        val doc = parse(JUnitXmlReport.render(RunReport.from(mixed)))
        val suite = doc.documentElement
        assertEquals("testsuite", suite.tagName)
        assertEquals("3", suite.getAttribute("tests"))
        assertEquals("1", suite.getAttribute("failures"))
        assertEquals("1", suite.getAttribute("errors"))
        assertEquals("0.200", suite.getAttribute("time"))
        val cases = doc.getElementsByTagName("testcase")
        assertEquals(3, cases.length)
        assertEquals(0, (cases.item(0) as org.w3c.dom.Element).getElementsByTagName("failure").length)
        assertEquals(1, doc.getElementsByTagName("failure").length)
        assertEquals(1, doc.getElementsByTagName("error").length)
    }

    @Test fun xmlEscaping() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;&apos;", JUnitXmlReport.escape("<a href=\"x\">&'"))
        assertEquals("ab", JUnitXmlReport.escape("a\u0000\u001Bb"))
        assertEquals("😀", JUnitXmlReport.escape("😀"))
        val nasty = listOf(RunOutcome.Failure(RequestTemplate("n<&\">'\u0001", "GET", "http://x/?a=1&b=2"), "bad <tag> & \"quote\""))
        val doc = parse(JUnitXmlReport.render(RunReport.from(nasty)))
        val tc = doc.getElementsByTagName("testcase").item(0) as org.w3c.dom.Element
        assertEquals("n<&\">'", tc.getAttribute("name"))
        val err = doc.getElementsByTagName("error").item(0) as org.w3c.dom.Element
        assertEquals("bad <tag> & \"quote\"", err.getAttribute("message"))
    }
}
