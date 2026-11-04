package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.ProxyAddress
import com.dan323.httpclient.core.domain.RequestOptions
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.`in`.RunRequest
import com.dan323.httpclient.core.port.out.FileReader
import com.dan323.httpclient.core.port.out.HttpExecutor

class RequestRunner(
    private val executor: HttpExecutor,
    private val files: FileReader,
) : RunRequest {
    override fun run(template: RequestTemplate, variables: Map<String, String>): HttpResponse =
        executor.execute(resolve(template, variables))

    fun resolve(t: RequestTemplate, env: Map<String, String>, extra: (String) -> String? = { null }): HttpRequest {
        val vars = VariableResolver.withFileVariables(env, t.fileVariables)
        return build(t, vars, extra)
    }

    private fun build(t: RequestTemplate, vars: Map<String, String>, extra: (String) -> String?): HttpRequest = HttpRequest(
        method = t.method,
        url = VariableResolver.resolve(t.url, vars, extra),
        headers = t.headers.map { Header(it.name, VariableResolver.resolve(it.value, vars, extra)) },
        body = when (val b = t.body) {
            null -> null
            is Body.Text -> VariableResolver.resolve(b.content, vars, extra).toByteArray()
            is Body.FromFile -> files.read(VariableResolver.resolve(b.path, vars, extra))
        },
        options = options(t, vars, extra),
    )

    /**
     * Settings (names are case-insensitive, unknown ones are ignored):
     * - `timeout`: whole number with optional `ms`/`s`/`m` suffix (any case), default seconds, must be positive.
     * - `no-redirect`: presence disables redirects; an optional value `true`/`false` (may use `{{vars}}`).
     * - `proxy`: `[http://]host[:port]` (port defaults to 80; IPv6 as `[::1]:8080`); no credentials, http only.
     * Invalid values fail here, at resolve time, with an `Invalid @setting` message.
     */
    private fun options(t: RequestTemplate, vars: Map<String, String>, extra: (String) -> String?): RequestOptions {
        val s = t.settings.mapKeys { it.key.lowercase() }
        fun value(name: String) = s[name]?.let { VariableResolver.resolve(it, vars, extra).trim() }
        return RequestOptions(
            timeoutMillis = value("timeout")?.let(::parseTimeout),
            followRedirects = value("no-redirect")?.let(::parseNoRedirect)?.not() ?: true,
            proxy = value("proxy")?.let(::parseProxy),
        )
    }

    private fun parseNoRedirect(raw: String): Boolean = when (raw.lowercase()) {
        "", "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("Invalid @no-redirect value: '$raw' (expected true or false)")
    }

    private val timeoutPattern = Regex("""(\d+)\s*(ms|s|m)?""", RegexOption.IGNORE_CASE)

    private fun parseTimeout(raw: String): Long {
        val m = timeoutPattern.matchEntire(raw) ?: throw IllegalArgumentException("Invalid @timeout value: '$raw'")
        val n = m.groupValues[1].toLongOrNull() ?: throw IllegalArgumentException("Invalid @timeout value: '$raw' (too large)")
        val factor = when (m.groupValues[2].lowercase()) {
            "ms" -> 1L
            "m" -> 60_000L
            else -> 1000L
        }
        val millis = try {
            Math.multiplyExact(n, factor)
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("Invalid @timeout value: '$raw' (too large)")
        }
        require(millis > 0) { "Invalid @timeout value: '$raw' (must be greater than zero)" }
        return millis
    }

    private fun parseProxy(raw: String): ProxyAddress {
        fun bad(why: String): Nothing = throw IllegalArgumentException("Invalid @proxy value: '$raw' ($why)")
        if (raw.isEmpty()) bad("empty")
        val scheme = raw.substringBefore("://", "").lowercase()
        if (scheme.isNotEmpty() && scheme != "http") bad("only http proxies are supported")
        val u = try {
            java.net.URI(if ("://" in raw) raw else "http://$raw")
        } catch (e: java.net.URISyntaxException) {
            bad("expected host:port, use [::1]:8080 for IPv6")
        }
        if (u.userInfo != null) bad("credentials are not supported")
        val host = u.host?.removeSurrounding("[", "]") ?: bad("expected host:port, use [::1]:8080 for IPv6")
        if (!u.path.isNullOrEmpty() && u.path != "/" || u.query != null || u.fragment != null) bad("unexpected path or query")
        return try {
            ProxyAddress(host, if (u.port == -1) 80 else u.port)
        } catch (e: IllegalArgumentException) {
            bad(e.message?.substringAfter(": ") ?: "invalid")
        }
    }
}
