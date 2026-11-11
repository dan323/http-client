package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.ReportEntry
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.out.HistoryRun
import com.dan323.httpclient.core.port.out.StoredResponse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** `2026-11-11 00:36:02` in [zone]. */
fun timeLabel(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String = timeFormat.format(at.atZone(zone))

/** `2026-11-11 00:36:02  ·  3 requests  ·  1 failed`; the failed part is left out when nothing failed. */
fun runLabel(run: HistoryRun, zone: ZoneId = ZoneId.systemDefault()): String {
    val r = run.report
    val failed = r.failedRequests
    val requests = "${r.requests} ${if (r.requests == 1) "request" else "requests"}"
    return timeLabel(run.timestamp, zone) + "  ·  " + requests + if (failed > 0) "  ·  $failed failed" else ""
}

/** `login  ·  200  ·  35 ms`, or `login  ·  failed: <message>` when the request got no response. */
fun entryLabel(e: ReportEntry): String = when {
    e.status != null -> "${e.name}  ·  ${e.status}" + (e.durationMillis?.let { "  ·  ${durationLabel(it)}" } ?: "")
    else -> "${e.name}  ·  failed" + (e.error?.let { ": $it" } ?: "")
}

/** `login (2026-11-11 00:36:02)`: names a stored response in the A / B marks. */
fun storedLabel(s: StoredResponse, zone: ZoneId = ZoneId.systemDefault()): String = "${s.requestName} (${timeLabel(s.timestamp, zone)})"

/** `Body cut to 5.0 MB (the response had 7.2 MB)`; null when the stored body is complete. */
fun truncationNotice(s: StoredResponse): String? =
    if (s.bodyTruncated) "Body cut to ${sizeLabel(s.response.body.size)} (the response had ${sizeLabel(s.originalBodySize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())})" else null

/** The stored response as a request template, so UIs can show it with the same code as a live response. */
fun StoredResponse.asRequest(): RequestTemplate = RequestTemplate(requestName.ifBlank { null }, method, url)
