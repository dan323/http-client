package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.AssertionResult
import com.dan323.httpclient.core.application.ReportEntry
import com.dan323.httpclient.core.application.RunReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSON rendering (and parsing back) of a [RunReport], with the totals included for consumers.
 *
 * Format contract: a top-level `version` (currently [VERSION]) is bumped on incompatible changes; [parse] rejects a
 * newer version and accepts a missing one as 1. `passed`, `totals` and each entry's `passed` are derived: they are
 * written for consumers and ignored by [parse], which recomputes them from `results`.
 * Entries may contain resolved-looking data from the run (URLs and error messages can include secrets such as tokens
 * in query strings); treat report files as sensitive CI artifacts.
 */
object JsonRunReport {
    const val VERSION = 1

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun render(report: RunReport): String = json.encodeToString(Dto.from(report)) + "\n"

    fun parse(text: String): RunReport {
        val dto = json.decodeFromString<Dto>(text)
        require(dto.version <= VERSION) { "Unsupported report version ${dto.version} (this build reads up to $VERSION)" }
        return dto.toReport()
    }

    @Serializable private data class AssertionDto(
        val assertion: String, val passed: Boolean, val expected: String? = null, val actual: String? = null, val message: String,
    )

    @Serializable private data class EntryDto(
        val name: String, val method: String, val url: String, val status: Int? = null, val durationMillis: Long? = null,
        val passed: Boolean, val assertions: List<AssertionDto> = emptyList(), val assertionsNotRun: Int = 0, val error: String? = null,
        val prerequisite: Boolean = false, val httpError: String? = null,
    )

    @Serializable private data class TotalsDto(
        val requests: Int, val passedRequests: Int, val failedRequests: Int,
        val assertionsPassed: Int, val assertionsFailed: Int, val assertionsNotRun: Int, val durationMillis: Long,
    )

    @Serializable private data class Dto(val version: Int = 1, val passed: Boolean, val totals: TotalsDto, val results: List<EntryDto>) {
        fun toReport() = RunReport(results.map { e ->
            ReportEntry(
                e.name, e.method, e.url, e.status, e.durationMillis,
                e.assertions.map { AssertionResult(it.assertion, it.passed, it.expected, it.actual, it.message) },
                e.assertionsNotRun, e.error, e.prerequisite, e.httpError,
            )
        })

        companion object {
            fun from(r: RunReport) = Dto(
                VERSION, r.passed,
                TotalsDto(r.requests, r.passedRequests, r.failedRequests, r.assertionsPassed, r.assertionsFailed, r.assertionsNotRun, r.totalDurationMillis),
                r.entries.map { e ->
                    EntryDto(
                        e.name, e.method, e.url, e.status, e.durationMillis, e.passed,
                        e.assertions.map { AssertionDto(it.assertion, it.passed, it.expected, it.actual, it.message) },
                        e.assertionsNotRun, e.error, e.prerequisite, e.httpError,
                    )
                },
            )
        }
    }
}
