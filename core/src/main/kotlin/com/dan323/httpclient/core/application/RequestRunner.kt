package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
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
     * Settings: `timeout` (number with optional `ms`/`s`/`m` suffix, default seconds), `no-redirect`,
     * `proxy` (`host:port`). Unknown settings are ignored.
     */
    private fun options(t: RequestTemplate, vars: Map<String, String>, extra: (String) -> String?): RequestOptions {
        val s = t.settings
        return RequestOptions(
            timeoutMillis = s["timeout"]?.let { parseTimeout(VariableResolver.resolve(it, vars, extra)) },
            followRedirects = "no-redirect" !in s,
            proxy = s["proxy"]?.let { VariableResolver.resolve(it, vars, extra).trim() }?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseTimeout(raw: String): Long {
        val m = Regex("""(\d+)\s*(ms|s|m)?""").matchEntire(raw.trim())
            ?: throw IllegalArgumentException("Invalid @timeout value: '$raw'")
        val n = m.groupValues[1].toLong()
        return when (m.groupValues[2]) {
            "ms" -> n
            "m" -> n * 60_000
            else -> n * 1000
        }
    }
}
