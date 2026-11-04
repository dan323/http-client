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
    ) : RunOutcome {
        val assertionsPassed: Boolean get() = assertions.all { it.passed }
    }

    data class Failure(override val request: RequestTemplate, val message: String) : RunOutcome
}
