package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.out.HttpExecutor
import com.dan323.httpclient.core.port.out.JsonExtractor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Runs requests in order and remembers the responses of named ones, so later requests can use
 * `{{name.response.body.$.path}}`, `{{name.response.body.*}}`, `{{name.response.headers.Header}}`
 * and `{{name.response.status}}`.
 *
 * Thread safety: safe for concurrent use. [run], [runAll] and [runChained] are serialised by a lock (one
 * request at a time, a whole `runAll` is atomic with respect to other runs), so chained requests never see a
 * half-updated session; [hasResponse] never blocks. `onOutcome`/`onReused` callbacks run while the lock is
 * held: they must not wait for another thread that uses this session (they are not moved out of the lock
 * because `runAll` reports each outcome as it completes, and later requests must not start before the
 * callback returned). A caller queued on the lock waits interruptibly: interrupting it abandons the wait with
 * [InterruptedException] (flag kept set) without running anything.
 */
class RequestSession(
    private val runner: RequestRunner,
    private val executor: HttpExecutor,
    json: JsonExtractor,
) {
    private val extractor = ResponseExtractor(json)
    private val assertionEvaluator = AssertionEvaluator(extractor)
    private val responses = ConcurrentHashMap<String, HttpResponse>()

    /** Serialises [run], [runAll] and [runChained]: a session runs one request at a time. */
    private val lock = ReentrantLock()

    /** Cookies from `Set-Cookie` responses, sent on later requests of this session; never shared across sessions. */
    private val cookies = CookieJar()
    private val redirects = RedirectFollower(executor, cookies)

    private fun send(request: HttpRequest): HttpResponse = redirects.send(request)

    private inline fun <T> locked(block: () -> T): T {
        try {
            lock.lockInterruptibly()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
        try { return block() } finally { lock.unlock() }
    }

    fun run(template: RequestTemplate, variables: Map<String, String>): HttpResponse = locked {
        val request = runner.resolve(template, variables, ::reference)
        val response = send(request)
        template.name?.let { responses[it] = response }
        response
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
    ): List<RunOutcome> = locked { requests.map { r ->
        val withAssertions = assertFor(r)
        val outcome = try {
            run(r, variables).let {
                RunOutcome.Success(r, it, if (withAssertions) evaluateAssertions(r, it, variables) else emptyList())
            }
        } catch (e: java.util.concurrent.CancellationException) {
            // cancelled (interrupted): stop the session, do not send the queued requests
            if (e.cause is InterruptedException) Thread.currentThread().interrupt()
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt() // keep the flag for the caller and do not send the queued requests
            throw e
        } catch (e: Exception) {
            RunOutcome.Failure(r, e.message ?: e::class.simpleName ?: "error", if (withAssertions) r.assertions.size else 0)
        }
        onOutcome(outcome)
        outcome
    } }

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
    ): List<RunOutcome> = locked {
        val chain = withDependencies(all, listOf(current))
        val (reused, toSend) = chain.partition { it !== current && it.name?.let(::hasResponse) == true }
        onReused(reused)
        runAll(toSend, variables, assertFor = { it === current }, onOutcome = onOutcome)
    }

    /** Substitutes `{{variables}}` and `{{name.response...}}` in each assertion, like request text, then evaluates it. */
    private fun evaluateAssertions(r: RequestTemplate, response: HttpResponse, variables: Map<String, String>): List<AssertionResult> {
        return r.assertions.map { a ->
            try {
                val resolved = runner.resolveText(a, variables, ::reference, r.fileVariables)
                val substituted = resolved != a
                val result = assertionEvaluator.evaluate(resolved, response, showValues = !substituted).copy(assertion = a)
                // Substituted values (env, variables, chained responses) may be secrets: keep them out of the result.
                // An invalid assertion keeps its reason (the evaluator already left the values out of it).
                if (!substituted) result
                else result.copy(
                    expected = null,
                    message = if (result.passed || AssertionEvaluator.isInvalid(result)) result.message
                    else "failed (expected value contains substitutions, not shown)",
                )
            } catch (e: RuntimeException) {
                when (e) {
                    is UndefinedVariableException, is InvalidDynamicVariableException, is VariableExpansionException ->
                        AssertionResult(a, false, null, null, "invalid assertion: ${e.message}")
                    else -> throw e
                }
            }
        }
    }

    /** True once a request with this `@name` has run in this session. */
    fun hasResponse(name: String): Boolean = responses.containsKey(name)

    private fun reference(name: String): String? {
        val split = name.indexOf(".response.")
        if (split <= 0) return null
        val response = responses[name.substring(0, split)] ?: return null
        val what = name.substring(split + ".response.".length)
        return extractor.extract(response, what)
    }
}
