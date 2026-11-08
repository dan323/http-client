package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.AssertionResult
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.application.HttpFileParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutcomeDisplayTest {
    private fun req(src: String) = HttpFileParser().parse(src).requests.single()
    private fun ok(src: String, code: Int, body: String, headers: List<Header> = emptyList()) =
        RunOutcome.Success(req(src), HttpResponse(code, headers, body.toByteArray(), 12), emptyList()).display() as OutcomeDisplay.Response

    @Test fun statusClassPerRange() {
        assertEquals(StatusClass.SUCCESS, ok("GET http://h/\n", 204, "").statusClass)
        assertEquals(StatusClass.REDIRECT, ok("GET http://h/\n", 302, "").statusClass)
        assertEquals(StatusClass.CLIENT_ERROR, ok("GET http://h/\n", 404, "").statusClass)
        assertEquals(StatusClass.SERVER_ERROR, ok("GET http://h/\n", 503, "").statusClass)
        assertEquals(StatusClass.UNKNOWN, ok("GET http://h/\n", 999, "").statusClass)
    }

    @Test fun headerTextAndLabels() {
        val d = ok("GET http://h/x\n", 404, "", listOf(Header("Content-Type", "text/plain"), Header("X-A", "1")))
        assertEquals("404 Not Found", d.statusLabel)
        assertEquals("GET http://h/x", d.title)
        assertEquals("GET http://h/x", d.summary)
        assertEquals("text/plain", d.contentType)
        assertEquals(listOf("Content-Type" to "text/plain", "X-A" to "1"), d.headers)
        assertEquals("12 ms", d.durationLabel)
        assertEquals(sizeLabel(0), d.sizeLabel)
    }

    @Test fun unknownStatusKeepsJustTheNumber() {
        assertEquals("599", ok("GET http://h/\n", 599, "").statusLabel.substringBefore(' '))
        assertEquals("999", ok("GET http://h/\n", 999, "").statusLabel)
    }

    @Test fun namedRequestUsesNameAsTitleAndSummaryHasBoth() {
        val d = ok("# @name login\nGET http://h/l\n", 200, "")
        assertEquals("login", d.title)
        assertEquals("login  ·  GET http://h/l", d.summary)
    }

    @Test fun jsonIsPrettyAndRawIsKept() {
        val d = ok("GET http://h/\n", 200, """{"a":1}""")
        assertTrue(d.isJson)
        assertEquals("{\n    \"a\": 1\n}", d.prettyBody)
        assertEquals("""{"a":1}""", d.rawBody)
    }

    @Test fun nonJsonStaysRaw() {
        for (b in listOf("plain", "42", "\"str\"", "{broken", "")) {
            val d = ok("GET http://h/\n", 200, b)
            assertFalse(d.isJson, b)
            assertEquals(b, d.prettyBody)
        }
        assertEquals("hi", prettyOrRaw("hi"))
        assertNull(prettyJsonOrNull("3"))
    }

    @Test fun failureAndReused() {
        val r = req("# @name n\nGET http://h/\n")
        val e = RunOutcome.Failure(r, "boom").display()
        assertIs<OutcomeDisplay.Error>(e)
        assertEquals("n", e.title)
        assertEquals("boom", e.message)
        val u = reusedDisplay(r)
        assertEquals("n", u.title)
        assertEquals("n  ·  GET http://h/", u.summary)
    }

    @Test fun saveAndLinkFeedback() {
        val r = req("GET http://h/\n")
        val resp = HttpResponse(200, emptyList(), ByteArray(0), 1)
        val plain = RunOutcome.Success(r, resp).display() as OutcomeDisplay.Response
        assertNull(plain.savedLabel)
        assertTrue(plain.notices.isEmpty())
        val saved = RunOutcome.Success(r, resp, savedTo = "out/a-1.json").display() as OutcomeDisplay.Response
        assertEquals("Saved to out/a-1.json", saved.savedLabel)
        assertEquals(listOf(OutcomeDisplay.Notice("Saved to out/a-1.json", false)), saved.notices)
        val failed = RunOutcome.Success(r, resp, saveError = "Cannot save response to '../x': outside", linkError = "Cannot keep the response link: disk full").display() as OutcomeDisplay.Response
        assertEquals(
            listOf(OutcomeDisplay.Notice("Cannot save response to '../x': outside", true), OutcomeDisplay.Notice("Cannot keep the response link: disk full", true)),
            failed.notices,
        )
        assertNull(failed.savedLabel)
        assertEquals(200, failed.status)
    }

    @Test fun assertionLinesAndSummary() {
        val r = req("GET http://h/\n")
        val results = listOf(
            AssertionResult("status == 200", true, "200", "200", "ok"),
            AssertionResult("body.id == 1", false, "1", "2", "expected 1, got 2"),
            AssertionResult("body.x exists", true, null, null, "ok"),
        )
        val d = RunOutcome.Success(r, HttpResponse(200, emptyList(), ByteArray(0), 1), results).display() as OutcomeDisplay.Response
        assertEquals(listOf("PASS status == 200", "FAIL body.id == 1 -- expected 1, got 2", "PASS body.x exists"), d.assertions.map { it.label })
        assertEquals("Assertions: 2 passed, 1 failed", d.assertionSummary)
        assertTrue(d.hasFailedAssertions)
    }

    @Test fun noAssertionsMeansNoSummary() {
        val d = ok("GET http://h/\n", 200, "")
        assertNull(d.assertionSummary)
        assertFalse(d.hasFailedAssertions)
    }

    @Test fun failureReportsAssertionsNotRun() {
        val r = req("GET http://h/\n")
        fun err(n: Int) = RunOutcome.Failure(r, "boom", n).display() as OutcomeDisplay.Error
        assertNull(err(0).assertionsNotRunLabel)
        assertEquals("1 assertion not run", err(1).assertionsNotRunLabel)
        assertEquals("3 assertions not run", err(3).assertionsNotRunLabel)
    }
}
