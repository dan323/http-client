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
    private val json: JsonExtractor,
) {
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

    private fun reference(name: String): String? {
        val split = name.indexOf(".response.")
        if (split <= 0) return null
        val response = responses[name.substring(0, split)] ?: return null
        val what = name.substring(split + ".response.".length)
        return when {
            what == "status" -> response.statusCode.toString()
            what == "body.*" -> response.bodyAsString()
            what.startsWith("body.") -> json.extract(response.bodyAsString(), what.removePrefix("body."))
            what.startsWith("headers.") -> response.header(what.removePrefix("headers."))
            else -> null
        }
    }
}
