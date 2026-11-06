package com.dan323.httpclient.core.port.out

import com.dan323.httpclient.core.application.RunReport
import com.dan323.httpclient.core.domain.HttpResponse
import java.nio.file.Path
import java.time.Instant

/** One recorded run of a `.http` file: its report and when it was recorded. [id] is opaque and unique per file. */
data class HistoryRun(val id: String, val timestamp: Instant, val report: RunReport)

/**
 * A response kept by the history. [index] is the 0-based position of the request in the run (= index in
 * [HistoryRun.report] entries). [response] holds the stored body, which is cut to the store's size limit:
 * [bodyTruncated] is true then and [originalBodySize] is the real size in bytes.
 */
data class StoredResponse(
    val runId: String,
    val timestamp: Instant,
    val index: Int,
    val requestName: String,
    val method: String,
    val url: String,
    val response: HttpResponse,
    val bodyTruncated: Boolean,
    val originalBodySize: Long,
)

/**
 * Remembers past runs and their responses per `.http` file. Stored responses are raw (they may contain secrets).
 * Implementations are safe for concurrent use, also from several processes; an unreadable or foreign entry is
 * skipped, never fatal. Methods throw [java.io.IOException] when storing fails.
 */
interface HistoryStore {
    /**
     * Records a run of [httpFile]. [responses] has one element per `report.entries` entry, in order: the response of
     * that request, or null when it failed before getting one. Old runs beyond the store's retention are dropped.
     */
    fun record(httpFile: Path, report: RunReport, responses: List<HttpResponse?>): HistoryRun

    /** Runs of [httpFile], newest first. */
    fun list(httpFile: Path): List<HistoryRun>

    /** The run with [runId], or null when it does not exist (or the id is not valid). */
    fun read(httpFile: Path, runId: String): HistoryRun?

    /** The stored response of request [index] of run [runId], or null when absent or the request got none. */
    fun readResponse(httpFile: Path, runId: String, index: Int): StoredResponse?

    /** The most recent stored response of the request named [requestName] (non-blank), or null. */
    fun latestResponse(httpFile: Path, requestName: String): StoredResponse?

    /**
     * Records the single [response] of a request that has a `<> path` link, under [key] (see `ResponseLinks.key`).
     * [report] has exactly one entry. The default stores a plain one-request run, which [latestLinked] finds again only
     * for a named request (key = name); implementations should keep the key.
     */
    fun recordLinked(httpFile: Path, key: String, report: RunReport, response: HttpResponse): HistoryRun =
        record(httpFile, report, listOf(response))

    /** The most recent stored response recorded for [key] (a request name, or the key of an unnamed request), or null. */
    fun latestLinked(httpFile: Path, key: String): StoredResponse? = latestResponse(httpFile, key)
}
