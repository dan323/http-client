package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.AssertionResult
import com.dan323.httpclient.core.application.ReportEntry
import com.dan323.httpclient.core.application.RunReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonRunReportTest {
    private val report = RunReport(listOf(
        ReportEntry("a \"q\" é", "GET", "http://x/a?b=1&c=<2>", 200, 12,
            listOf(AssertionResult("status == 200", true, "200", "200", "ok"), AssertionResult("body.\$.x exists", false, null, null, "missing")), 0, null),
        ReportEntry("b", "POST", "http://x/b", null, null, emptyList(), 1, "boom\nline2"),
    ))

    @Test fun roundTrips() {
        assertEquals(report, JsonRunReport.parse(JsonRunReport.render(report)))
    }

    @Test fun includesTotals() {
        val root = Json.parseToJsonElement(JsonRunReport.render(report)).jsonObject
        assertEquals(false, root["passed"]!!.jsonPrimitive.boolean)
        val totals = root["totals"]!!.jsonObject
        assertEquals(2, totals["requests"]!!.jsonPrimitive.int)
        assertEquals(1, totals["assertionsFailed"]!!.jsonPrimitive.int)
        assertEquals(1, totals["assertionsNotRun"]!!.jsonPrimitive.int)
    }

    @Test fun writesVersionAndRoundTripsEmptyAndPrerequisite() {
        val root = Json.parseToJsonElement(JsonRunReport.render(report)).jsonObject
        assertEquals(1, root["version"]!!.jsonPrimitive.int)
        val empty = RunReport(emptyList())
        assertEquals(empty, JsonRunReport.parse(JsonRunReport.render(empty)))
        assertTrue(JsonRunReport.parse(JsonRunReport.render(empty)).passed)
        val pre = RunReport(listOf(ReportEntry("p", "GET", "http://x", 200, 1, emptyList(), 0, null, prerequisite = true)))
        assertEquals(pre, JsonRunReport.parse(JsonRunReport.render(pre)))
    }

    @Test fun parseRejectsNewerVersionAndIgnoresDerivedFields() {
        val text = JsonRunReport.render(report)
        assertFailsWith<IllegalArgumentException> { JsonRunReport.parse(text.replace("\"version\": 1", "\"version\": 2")) }
        // derived fields are recomputed: a hand-edited "passed" does not change the parsed report
        val edited = text.replaceFirst("\"passed\": false", "\"passed\": true")
        assertEquals(report, JsonRunReport.parse(edited))
        assertEquals(false, JsonRunReport.parse(edited).passed)
    }

    @Test fun httpErrorRoundTripsAndIsOptional() {
        val failing = RunReport(listOf(ReportEntry("a", "GET", "http://x", 500, 3, emptyList(), 0, null, httpError = "HTTP 500")))
        val text = JsonRunReport.render(failing)
        assertEquals(failing, JsonRunReport.parse(text))
        assertEquals(false, Json.parseToJsonElement(text).jsonObject["passed"]!!.jsonPrimitive.boolean)
        assertEquals("HTTP 500", Json.parseToJsonElement(text).jsonObject["results"]!!.let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject["httpError"]!!.jsonPrimitive.content })
        // reports written before the field existed still parse
        assertEquals(report, JsonRunReport.parse(JsonRunReport.render(report)))
        val old = JsonRunReport.render(report).replace(Regex(""",\s*"httpError": null"""), "")
        assertEquals(report, JsonRunReport.parse(old))
    }
}
