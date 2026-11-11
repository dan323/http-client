package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponseDiffTest {
    private fun resp(status: Int = 200, body: String = "", vararg headers: Pair<String, String>, ms: Long = 10) =
        HttpResponse(status, headers.map { Header(it.first, it.second) }, body.toByteArray(), ms)

    private fun lines(d: ResponseDiff) = (d.body as BodyDiff.Lines).lines.map { it.label }

    @Test fun identicalResponsesAreIdentical() {
        val d = diffResponses(resp(200, "x", "A" to "1"), resp(200, "x", "A" to "1", ms = 99))
        assertTrue(d.identical)
        assertEquals("Status 200 in both", d.statusLabel)
        assertEquals("A: 10 ms  ·  B: 99 ms", diffResponses(resp(), resp(ms = 99)).durationsLabel)
    }

    @Test fun statusChangeIsLabelled() {
        val d = diffResponses(resp(200), resp(404))
        assertTrue(d.statusChanged)
        assertEquals("A: 200  →  B: 404", d.statusLabel)
    }

    @Test fun headersAddedRemovedChangedAreSortedAndCaseInsensitive() {
        val d = diffResponses(
            resp(200, "", "X-Gone" to "1", "content-type" to "a", "Same" to "s"),
            resp(200, "", "X-New" to "2", "Content-Type" to "b", "same" to "s"),
        )
        assertEquals(
            listOf(
                HeaderChange.Changed("content-type", "a", "b"),
                HeaderChange.Removed("X-Gone", "1"),
                HeaderChange.Added("X-New", "2"),
            ),
            d.headers,
        )
    }

    @Test fun repeatedHeadersAreJoined() {
        val d = diffResponses(resp(200, "", "Set-Cookie" to "a=1", "Set-Cookie" to "b=2"), resp(200, "", "Set-Cookie" to "a=1"))
        assertEquals(listOf(HeaderChange.Changed("Set-Cookie", "a=1, b=2", "a=1")), d.headers)
    }

    @Test fun textBodiesAreDiffedByLine() {
        val d = diffResponses(resp(body = "one\ntwo\nthree"), resp(body = "one\n2\nthree\nfour"))
        assertEquals(listOf("  one", "- two", "+ 2", "  three", "+ four"), lines(d))
    }

    @Test fun jsonKeyOrderAndSpacingDoNotCountButValuesDo() {
        assertEquals(BodyDiff.Same, diffResponses(resp(body = "{\"a\":1,\"b\":2}"), resp(body = "{ \"a\": 1,\n \"b\": 2 }")).body)
        val d = diffResponses(resp(body = "{\"a\":1}"), resp(body = "{\"a\":2}"))
        assertEquals(listOf("  {", "-     \"a\": 1", "+     \"a\": 2", "  }"), lines(d))
    }

    @Test fun emptyAndMissingBodies() {
        assertEquals(BodyDiff.Same, diffResponses(resp(body = ""), resp(body = "")).body)
        assertEquals(setOf("- ", "+ x"), lines(diffResponses(resp(body = ""), resp(body = "x"))).toSet())
    }

    @Test fun binaryBodiesShowOnlySizes() {
        val bin = HttpResponse(200, emptyList(), byteArrayOf(0x41, -1, -2), 1)
        val d = diffResponses(bin, resp(body = "text"))
        assertEquals(BodyDiff.Skipped("a body is not text", 3, 4), d.body)
    }

    @Test fun largeBodiesShowOnlySizes() {
        val big = "x".repeat(ResponseDiffLimits.MAX_BODY_BYTES + 1)
        val d = diffResponses(resp(body = big), resp(body = "y"))
        assertEquals("a body is larger than 1 MB", (d.body as BodyDiff.Skipped).reason)
    }

    @Test fun bodiesWithTooManyLinesShowOnlySizes() {
        val many = "a\n".repeat(ResponseDiffLimits.MAX_LINES + 1)
        val d = diffResponses(resp(body = many), resp(body = "b"))
        assertEquals("a body has more than 20000 lines", (d.body as BodyDiff.Skipped).reason)
    }

    @Test fun aHugeDifferingMiddleIsSkippedButACommonStartAndEndAreNotCounted() {
        val head = List(3000) { "h$it" }
        val tail = List(3000) { "t$it" }
        // differing middle of 10 x 10 lines between long common ends: compared
        val ok = diffLines(head + List(10) { "a$it" } + tail, head + List(10) { "b$it" } + tail)
        assertEquals(6000 + 20, ok!!.size)
        // 3000 x 3000 differing lines: over the cell cap
        assertNull(diffLines(List(3000) { "a$it" }, List(3000) { "b$it" }))
        val d = diffResponses(resp(body = List(3000) { "a$it" }.joinToString("\n")), resp(body = List(3000) { "b$it" }.joinToString("\n")))
        assertEquals("too many lines differ to compare", (d.body as BodyDiff.Skipped).reason)
    }

    @Test fun diffLinesKeepsOrderForInsertionsAndDeletions() {
        assertEquals(listOf("  a", "+ b", "  c"), diffLines(listOf("a", "c"), listOf("a", "b", "c"))!!.map { it.label })
        assertEquals(listOf("  a", "- b", "  c"), diffLines(listOf("a", "b", "c"), listOf("a", "c"))!!.map { it.label })
        assertEquals(emptyList(), diffLines(emptyList(), emptyList()))
    }

    @Test fun jsonKeyOrderIsIgnoredAtEveryLevelButArrayOrderIsNot() {
        val a = resp(body = "{\"b\":{\"y\":1,\"x\":2},\"a\":[1,2]}")
        assertEquals(BodyDiff.Same, diffResponses(a, resp(body = "{\"a\":[1,2],\"b\":{\"x\":2,\"y\":1}}")).body)
        assertTrue(diffResponses(a, resp(body = "{\"a\":[2,1],\"b\":{\"x\":2,\"y\":1}}")).body is BodyDiff.Lines)
    }

    private fun stored(body: String, cut: Boolean, original: Long = body.length.toLong()) = com.dan323.httpclient.core.port.out.StoredResponse(
        "r", java.time.Instant.EPOCH, 0, "n", "GET", "http://x", resp(body = body), cut, original,
    )

    @Test fun equalCutBodiesAreNotReportedAsTheSame() {
        val d = diffStoredResponses(stored("abc", true, 10), stored("abc", true, 20))
        val skipped = d.body as BodyDiff.Skipped
        assertEquals(10, skipped.sizeA); assertEquals(20, skipped.sizeB)
        assertTrue(!d.identical)
        assertEquals(BodyDiff.Same, diffStoredResponses(stored("abc", false), stored("abc", false)).body)
    }
}
