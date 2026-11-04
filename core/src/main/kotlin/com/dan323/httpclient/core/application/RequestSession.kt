package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.out.HttpExecutor
import com.dan323.httpclient.core.port.out.JsonExtractor
import java.net.URI

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
    private val assertionEvaluator = AssertionEvaluator(extractor)
    private val responses = mutableMapOf<String, HttpResponse>()

    /** Cookies from `Set-Cookie` responses, sent on later requests of this session; never shared across sessions. */
    private val cookies = CookieJar()

    /** Adds the jar's `Cookie` header unless the request opted out or sets its own `Cookie` header. */
    private fun withCookies(r: HttpRequest): HttpRequest {
        if (r.headers.any { it.name.equals("Cookie", ignoreCase = true) }) return r
        val cookie = cookies.headerFor(r.url) ?: return r
        return r.copy(headers = r.headers + Header("Cookie", cookie))
    }

    /**
     * Sends [request]. With the cookie jar off the executor handles everything (including redirects).
     * With the jar on, redirects are followed here, hop by hop, with the executor told not to follow any:
     * the `Cookie` header is recomputed from the jar for every hop's URL, `Set-Cookie` of every hop (the
     * `POST /login` -> `302` + `Set-Cookie` flow) is stored, and cookie jar state never leaks to another host
     * through the executor's own redirect handling. Rules: at most [MAX_REDIRECTS] hops (then an error, like
     * the JDK), `@no-redirect` returns the first response, 301/302 on a non-GET/HEAD method and 303 switch to
     * GET without body (and drop Content-* headers), 307/308 keep method and body, and an explicit
     * `Cookie`, `Authorization` or `Proxy-Authorization` header written in the request is dropped when the
     * redirect leaves the origin (scheme, host, port). Non-http(s) or missing `Location` ends the chain with
     * the redirect response itself. The reported duration is the sum over all hops.
     */
    private fun send(request: HttpRequest): HttpResponse {
        if (!request.options.useCookieJar) return executor.execute(request)
        val follow = request.options.followRedirects
        var current = request.copy(options = request.options.copy(followRedirects = false))
        var total = 0L
        var hops = 0
        while (true) {
            val sent = withCookies(current)
            val response = executor.execute(sent)
            total += response.durationMillis
            cookies.store(sent.url, response.headers)
            val next = if (follow) redirectTarget(current, response) else null
            if (next == null) return response.copy(durationMillis = total)
            if (++hops > MAX_REDIRECTS) throw IllegalStateException("Too many redirects (more than $MAX_REDIRECTS)")
            current = next
        }
    }

    private fun redirectTarget(request: HttpRequest, response: HttpResponse): HttpRequest? {
        if (response.statusCode !in setOf(301, 302, 303, 307, 308)) return null
        val location = response.header("Location")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val from = try { URI(request.url) } catch (e: Exception) { return null }
        val to = try { from.resolve(location) } catch (e: Exception) { return null }
        if (to.scheme?.lowercase() !in setOf("http", "https") || to.rawAuthority == null) return null
        val toGet = response.statusCode == 303 && request.method != "HEAD" ||
            (response.statusCode == 301 || response.statusCode == 302) && request.method == "POST"
        val sameOrigin = from.scheme.equals(to.scheme, true) && from.host.equals(to.host, true) && port(from) == port(to) &&
            (from.host != null || from.rawAuthority.equals(to.rawAuthority, true))
        val dropped = buildSet {
            if (!sameOrigin) addAll(listOf("cookie", "authorization", "proxy-authorization"))
            if (toGet) addAll(listOf("content-type", "content-length", "content-encoding", "transfer-encoding"))
        }
        return request.copy(
            method = if (toGet) "GET" else request.method,
            url = to.toString(),
            headers = request.headers.filter { it.name.lowercase() !in dropped },
            body = if (toGet) null else request.body,
        )
    }

    private fun port(u: URI) = if (u.port != -1) u.port else if (u.scheme.equals("https", true)) 443 else 80

    fun run(template: RequestTemplate, variables: Map<String, String>): HttpResponse {
        val request = runner.resolve(template, variables, ::reference)
        val response = send(request)
        template.name?.let { responses[it] = response }
        return response
    }

    /**
     * Runs [requests] in order, reporting each outcome as it completes; a failure does not stop the rest.
     * Assertions are evaluated only for requests where [assertFor] is true (default: all); a request that
     * fails reports its assertions as not run ([RunOutcome.Failure.assertionsNotRun]).
     */
    fun runAll(
        requests: List<RequestTemplate>,
        variables: Map<String, String>,
        assertFor: (RequestTemplate) -> Boolean = { true },
        onOutcome: (RunOutcome) -> Unit = {},
    ): List<RunOutcome> = requests.map { r ->
        val withAssertions = assertFor(r)
        val outcome = try {
            run(r, variables).let {
                RunOutcome.Success(r, it, if (withAssertions) evaluateAssertions(r, it, variables) else emptyList())
            }
        } catch (e: Exception) {
            RunOutcome.Failure(r, e.message ?: e::class.simpleName ?: "error", if (withAssertions) r.assertions.size else 0)
        }
        onOutcome(outcome)
        outcome
    }

    /**
     * Runs [current] after the named requests it chains on ([withDependencies]). Dependencies that already
     * ran in this session are not sent again: their stored response is reused. [current] is always sent.
     * [onReused] is told first which requests were skipped. Only [current]'s assertions are evaluated;
     * the prerequisites run for their responses and their assertions do not affect the result.
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
        return runAll(toSend, variables, assertFor = { it === current }, onOutcome = onOutcome)
    }

    /** Substitutes `{{variables}}` and `{{name.response...}}` in each assertion, like request text, then evaluates it. */
    private fun evaluateAssertions(r: RequestTemplate, response: HttpResponse, variables: Map<String, String>): List<AssertionResult> {
        val vars = VariableResolver.withFileVariables(variables, r.fileVariables)
        return r.assertions.map { a ->
            try {
                assertionEvaluator.evaluate(VariableResolver.resolve(a, vars, ::reference), response).copy(assertion = a)
            } catch (e: UndefinedVariableException) {
                AssertionResult(a, false, null, null, "invalid assertion: ${e.message}")
            }
        }
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

    private companion object {
        const val MAX_REDIRECTS = 10
    }
}
