package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.out.HttpExecutor
import com.dan323.httpclient.core.port.out.JsonExtractor

/**
 * Runs requests in order and remembers the responses of named ones, so later requests can use
 * `{{name.response.body.$.path}}`, `{{name.response.body.*}}`, `{{name.response.headers.Header}}`
 * and `{{name.response.status}}`.
 */
class RequestSession(
    private val runner: RequestRunner,
    private val executor: HttpExecutor,
    json: JsonExtractor,
) {
    private val extractor = ResponseExtractor(json)
    private val responses = mutableMapOf<String, HttpResponse>()

    fun run(template: RequestTemplate, variables: Map<String, String>): HttpResponse {
        val response = executor.execute(runner.resolve(template, variables, ::reference))
        template.name?.let { responses[it] = response }
        return response
    }

    /** Runs [requests] in order, reporting each outcome as it completes; a failure does not stop the rest. */
    fun runAll(
        requests: List<RequestTemplate>,
        variables: Map<String, String>,
        onOutcome: (RunOutcome) -> Unit = {},
    ): List<RunOutcome> = requests.map { r ->
        val outcome = try {
            RunOutcome.Success(r, run(r, variables))
        } catch (e: Exception) {
            RunOutcome.Failure(r, e.message ?: e::class.simpleName ?: "error")
        }
        onOutcome(outcome)
        outcome
    }

    /**
     * Runs [current] after the named requests it chains on ([withDependencies]). Dependencies that already
     * ran in this session are not sent again: their stored response is reused. [current] is always sent.
     * [onReused] is told first which requests were skipped.
     */
    fun runChained(
        all: List<RequestTemplate>,
        current: RequestTemplate,
        variables: Map<String, String>,
        onReused: (List<RequestTemplate>) -> Unit = {},
        onOutcome: (RunOutcome) -> Unit = {},
    ): List<RunOutcome> {
        val chain = withDependencies(all, listOf(current))
        val (reused, toSend) = chain.partition { it !== current && it.name?.let(::hasResponse) == true }
        onReused(reused)
        return runAll(toSend, variables, onOutcome)
    }

    /** True once a request with this `@name` has run in this session. */
    fun hasResponse(name: String): Boolean = name in responses

    private fun reference(name: String): String? {
        val split = name.indexOf(".response.")
        if (split <= 0) return null
        val response = responses[name.substring(0, split)] ?: return null
        val what = name.substring(split + ".response.".length)
        return extractor.extract(response, what)
    }
}
