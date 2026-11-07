package com.dan323.httpclient.core.application

/** One request of a run. [url] is the URL as written in the file (variables unresolved). */
data class ReportEntry(
    val name: String,
    val method: String,
    val url: String,
    /** HTTP status; null when the request failed before getting a response. */
    val status: Int?,
    val durationMillis: Long?,
    val assertions: List<AssertionResult>,
    val assertionsNotRun: Int,
    /** Why the request itself failed (never a failed assertion); null otherwise. */
    val error: String?,
    /** True for requests that only ran to feed `{{name.response...}}` chaining; their assertions are not evaluated. */
    val prerequisite: Boolean = false,
    /** Set (for example `HTTP 503`) when the run was asked to fail on HTTP error statuses and this response had one; null otherwise. */
    val httpError: String? = null,
) {
    val passed: Boolean get() = error == null && httpError == null && assertions.all { it.passed }
}

/** Pure summary of a run built from its [RunOutcome]s; exporters (JSON, JUnit XML) live in the adapters. */
data class RunReport(val entries: List<ReportEntry>) {
    val requests: Int get() = entries.size
    val passedRequests: Int get() = entries.count { it.passed }
    val failedRequests: Int get() = entries.count { !it.passed }
    val assertionsPassed: Int get() = entries.sumOf { e -> e.assertions.count { it.passed } }
    val assertionsFailed: Int get() = entries.sumOf { e -> e.assertions.count { !it.passed } }
    val assertionsNotRun: Int get() = entries.sumOf { it.assertionsNotRun }
    val totalDurationMillis: Long get() = entries.sumOf { it.durationMillis ?: 0L }
    val passed: Boolean get() = entries.all { it.passed }

    companion object {
        /**
         * With [failOnHttpError], a response with status >= 400 ([RunOutcome.Success.httpError]) fails its entry, except for
         * prerequisites (like assertions, the status of requests that only feed chaining is not judged).
         */
        fun from(
            outcomes: List<RunOutcome>,
            failOnHttpError: Boolean = false,
            isPrerequisite: (RunOutcome) -> Boolean = { false },
        ): RunReport = RunReport(outcomes.map { entry(it, isPrerequisite(it), failOnHttpError) })

        private fun entry(outcome: RunOutcome, prerequisite: Boolean, failOnHttpError: Boolean): ReportEntry {
            val r = outcome.request
            val name = r.name ?: "${r.method} ${r.url}"
            return when (outcome) {
                is RunOutcome.Success -> ReportEntry(
                    name, r.method, r.url, outcome.response.statusCode, outcome.response.durationMillis,
                    outcome.assertions, 0, null, prerequisite,
                    if (failOnHttpError && !prerequisite && outcome.httpError) "HTTP ${outcome.response.statusCode}" else null,
                )
                is RunOutcome.Failure -> ReportEntry(
                    name, r.method, r.url, null, null, emptyList(), outcome.assertionsNotRun, outcome.message, prerequisite,
                )
            }
        }
    }
}
