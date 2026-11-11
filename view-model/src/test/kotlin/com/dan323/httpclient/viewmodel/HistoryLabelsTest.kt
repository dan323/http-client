package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.ReportEntry
import com.dan323.httpclient.core.application.RunReport
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.HistoryRun
import com.dan323.httpclient.core.port.out.StoredResponse
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryLabelsTest {
    private val at = Instant.parse("2026-11-11T00:36:02Z")
    private fun ok(name: String = "login") = ReportEntry(name, "GET", "http://x", 200, 35, emptyList(), 0, null)
    private fun failed() = ReportEntry("me", "GET", "http://x", null, null, emptyList(), 0, "connection refused")
    private fun stored(truncated: Boolean = false, original: Long = 10, name: String = "login") =
        StoredResponse("r1", at, 0, name, "GET", "http://x/a", HttpResponse(200, emptyList(), ByteArray(10), 1), truncated, original)

    @Test fun timeIsFormattedInTheGivenZone() {
        assertEquals("2026-11-11 00:36:02", timeLabel(at, ZoneOffset.UTC))
        assertEquals("2026-11-11 01:36:02", timeLabel(at, ZoneOffset.ofHours(1)))
    }

    @Test fun runLabelCountsRequestsAndFailures() {
        assertEquals("2026-11-11 00:36:02  ·  1 request", runLabel(HistoryRun("r", at, RunReport(listOf(ok()))), ZoneOffset.UTC))
        assertEquals(
            "2026-11-11 00:36:02  ·  2 requests  ·  1 failed",
            runLabel(HistoryRun("r", at, RunReport(listOf(ok(), failed()))), ZoneOffset.UTC),
        )
    }

    @Test fun entryLabelShowsStatusOrFailure() {
        assertEquals("login  ·  200  ·  35 ms", entryLabel(ok()))
        assertEquals("me  ·  failed: connection refused", entryLabel(failed()))
        assertEquals("x  ·  failed", entryLabel(failed().copy(name = "x", error = null)))
        assertEquals("a  ·  200", entryLabel(ok("a").copy(durationMillis = null)))
    }

    @Test fun storedLabelAndTruncationNotice() {
        assertEquals("login (2026-11-11 00:36:02)", storedLabel(stored(), ZoneOffset.UTC))
        assertNull(truncationNotice(stored()))
        assertEquals("Body cut to 10 B (the response had 7.0 MB)", truncationNotice(stored(true, 7_340_032)))
    }

    @Test fun storedResponseBecomesARequestTemplate() {
        val r = stored().asRequest()
        assertEquals(Triple("login", "GET", "http://x/a"), Triple(r.name, r.method, r.url))
        assertNull(stored(name = "").asRequest().name)
    }
}
