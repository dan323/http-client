package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.AssertionResult
import com.dan323.httpclient.core.application.ReportEntry
import com.dan323.httpclient.core.application.RunReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** JSON rendering (and parsing back) of a [RunReport], with the totals included for consumers. */
object JsonRunReport {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun render(report: RunReport): String = json.encodeToString(Dto.from(report)) + "\n"

    fun parse(text: String): RunReport = json.decodeFromString<Dto>(text).toReport()

    @Serializable private data class AssertionDto(
        val assertion: String, val passed: Boolean, val expected: String? = null, val actual: String? = null, val message: String,
    )

    @Serializable private data class EntryDto(
        val name: String, val method: String, val url: String, val status: Int? = null, val durationMillis: Long? = null,
        val passed: Boolean, val assertions: List<AssertionDto> = emptyList(), val assertionsNotRun: Int = 0, val error: String? = null,
    )

    @Serializable private data class TotalsDto(
        val requests: Int, val passedRequests: Int, val failedRequests: Int,
        val assertionsPassed: Int, val assertionsFailed: Int, val assertionsNotRun: Int, val durationMillis: Long,
    )

    @Serializable private data class Dto(val passed: Boolean, val totals: TotalsDto, val results: List<EntryDto>) {
        fun toReport() = RunReport(results.map { e ->
            ReportEntry(
                e.name, e.method, e.url, e.status, e.durationMillis,
                e.assertions.map { AssertionResult(it.assertion, it.passed, it.expected, it.actual, it.message) },
                e.assertionsNotRun, e.error,
            )
        })

        companion object {
            fun from(r: RunReport) = Dto(
                r.passed,
                TotalsDto(r.requests, r.passedRequests, r.failedRequests, r.assertionsPassed, r.assertionsFailed, r.assertionsNotRun, r.totalDurationMillis),
                r.entries.map { e ->
                    EntryDto(
                        e.name, e.method, e.url, e.status, e.durationMillis, e.passed,
                        e.assertions.map { AssertionDto(it.assertion, it.passed, it.expected, it.actual, it.message) },
                        e.assertionsNotRun, e.error,
                    )
                },
            )
        }
    }
}
