package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.AssertionResult
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

class JUnitXmlReportTest {
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
        assertEquals("1. n<&\">'", tc.getAttribute("name"))
        val err = doc.getElementsByTagName("error").item(0) as org.w3c.dom.Element
        assertEquals("bad <tag> & \"quote\"", err.getAttribute("message"))
    }

    @Test fun emptyReportRendersZeroTests() {
        val suite = parse(JUnitXmlReport.render(RunReport(emptyList()))).documentElement
        assertEquals("0", suite.getAttribute("tests"))
        assertEquals("0", suite.getAttribute("failures"))
        assertEquals("0.000", suite.getAttribute("time"))
        assertEquals(0, suite.getElementsByTagName("testcase").length)
    }

    @Test fun durationsOfASecondOrMore() {
        val suite = parse(JUnitXmlReport.render(RunReport.from(listOf(ok("a", 1234, pass("s")), ok("b", 61_005, pass("s")))))).documentElement
        assertEquals("62.239", suite.getAttribute("time"))
        val cases = suite.getElementsByTagName("testcase")
        assertEquals("1.234", (cases.item(0) as org.w3c.dom.Element).getAttribute("time"))
        assertEquals("61.005", (cases.item(1) as org.w3c.dom.Element).getAttribute("time"))
    }

    @Test fun multipleFailedAssertionsAreOneFailurePerTestcase() {
        val r = RunReport.from(listOf(ok("a", 5, fail("status == 200"), pass("x"), AssertionResult("body.\$.y exists", false, null, null, "y missing"))))
        val suite = parse(JUnitXmlReport.render(r)).documentElement
        assertEquals("1", suite.getAttribute("tests"))
        assertEquals("1", suite.getAttribute("failures"))
        assertEquals(1, suite.getElementsByTagName("failure").length)
        val f = suite.getElementsByTagName("failure").item(0) as org.w3c.dom.Element
        assertEquals("expected 200 but was 500\ny missing", f.getAttribute("message"))
        assertTrue(f.textContent.contains("status == 200 -- expected 200 but was 500"))
        assertTrue(f.textContent.contains("body.\$.y exists -- y missing"))
    }

    @Test fun newlinesTabsAndCarriageReturnsSurviveInAttributes() {
        assertEquals("a&#10;b&#13;c&#9;d", JUnitXmlReport.escapeAttribute("a\nb\rc\td"))
        assertEquals("a\nb&#13;", JUnitXmlReport.escape("a\nb\r"))
        val doc = parse(JUnitXmlReport.render(RunReport.from(listOf(RunOutcome.Failure(req("n"), "l1\nl2\r\nl3\tx")))))
        assertEquals("l1\nl2\r\nl3\tx", (doc.getElementsByTagName("error").item(0) as org.w3c.dom.Element).getAttribute("message"))
    }

    @Test fun testcaseIdentityIsUniqueForDuplicates() {
        val r = RunReport.from(listOf(ok(null, 1, pass("s")), ok(null, 1, pass("s")), ok("dup", 1), ok("dup", 1)))
        val cases = parse(JUnitXmlReport.render(r, "f.http")).getElementsByTagName("testcase")
        val ids = (0 until cases.length).map { (cases.item(it) as org.w3c.dom.Element).let { e -> e.getAttribute("classname") + "|" + e.getAttribute("name") } }
        assertEquals(4, ids.toSet().size)
        assertEquals("f.http|1. GET http://x/a", ids[0])
    }

    @Test fun passingPrerequisitesAreOmittedButFailedOnesKept() {
        val outcomes = listOf(ok("pre", 1), RunOutcome.Failure(req("pre2"), "boom"), ok("main", 1, pass("s")))
        val r = RunReport.from(outcomes) { it.request.name?.startsWith("pre") == true }
        assertEquals(listOf(true, true, false), r.entries.map { it.prerequisite })
        val suite = parse(JUnitXmlReport.render(r)).documentElement
        assertEquals("2", suite.getAttribute("tests"))
        assertEquals("1", suite.getAttribute("errors"))
        val cases = suite.getElementsByTagName("testcase")
        assertEquals("2. pre2", (cases.item(0) as org.w3c.dom.Element).getAttribute("name"))
        assertEquals("3. main", (cases.item(1) as org.w3c.dom.Element).getAttribute("name"))
    }
}
