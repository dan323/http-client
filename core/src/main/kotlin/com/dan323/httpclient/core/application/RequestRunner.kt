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
    private val dynamic: DynamicVariables,
    /** Total timeout for a request without `# @timeout`; null means none (the front ends pass [RequestTimeouts.DEFAULT_MILLIS]). */
    private val defaultTimeoutMillis: Long? = null,
) : RunRequest {
    constructor(executor: HttpExecutor, files: FileReader) : this(executor, files, DynamicVariables())

    /** Substitutes variables in free text (e.g. an assertion) exactly as for request text. */
    fun resolveText(
        text: String,
        variables: Map<String, String>,
        extra: (String) -> String? = { null },
        fileVariables: Map<String, String> = emptyMap(),
        onSubstitute: (name: String, value: String) -> Unit = { _, _ -> },
    ): String = VariableResolver.resolve(text, variables, extra, dynamic, fileVariables, onSubstitute)

    override fun run(template: RequestTemplate, variables: Map<String, String>): HttpResponse =
        executor.execute(resolve(template, variables))

    /** [onSubstitute] is told each substituted `{{name}}` and its value (used to keep values out of failure messages). */
    fun resolve(
        t: RequestTemplate,
        env: Map<String, String>,
        extra: (String) -> String? = { null },
        onSubstitute: (name: String, value: String) -> Unit = { _, _ -> },
    ): HttpRequest =
        build(t, { onSubstitute("Authorization", it) }) { VariableResolver.resolve(it, env, extra, dynamic, t.fileVariables, onSubstitute) }

    private fun build(t: RequestTemplate, secret: (String) -> Unit, text: (String) -> String): HttpRequest = HttpRequest(
        method = t.method,
        url = text(t.url),
        headers = t.headers.map {
            val value = text(it.value)
            Header(it.name, if (it.name.trim().equals("Authorization", ignoreCase = true)) BasicAuth.encode(value, secret) else value)
        },
        body = when (val b = t.body) {
            null -> null
            is Body.Text -> text(b.content).toByteArray()
            is Body.FromFile -> files.read(text(b.path))
        },
        options = options(t, text),
    )

    private fun options(t: RequestTemplate, text: (String) -> String): RequestOptions =
        RequestOptionsParser.parse(t.settings, defaultTimeoutMillis, text)
}
