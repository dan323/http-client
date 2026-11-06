package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate

sealed interface RunOutcome {
    val request: RequestTemplate

    /** [assertions] holds one result per `# @assert` line of the request (empty when it has none). */
    data class Success(
        override val request: RequestTemplate,
        val response: HttpResponse,
        val assertions: List<AssertionResult> = emptyList(),
        /** Where the `>>` / `>>!` directive saved the body (relative to the .http file, `/` separators); null when none or it failed. */
        val savedTo: String? = null,
        /** Why the `>>` / `>>!` save failed (the response itself is fine); never contains the body. */
        val saveError: String? = null,
        /** Why recording the `<>` link failed; the response itself is fine. */
        val linkError: String? = null,
    ) : RunOutcome {
        val assertionsPassed: Boolean get() = assertions.all { it.passed }
    }

    /** [assertionsNotRun] counts the request's `# @assert` lines that could not be evaluated because it failed. */
    data class Failure(override val request: RequestTemplate, val message: String, val assertionsNotRun: Int = 0) : RunOutcome
}
