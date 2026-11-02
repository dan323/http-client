package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
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

    fun resolve(t: RequestTemplate, vars: Map<String, String>): HttpRequest = HttpRequest(
        method = t.method,
        url = VariableResolver.resolve(t.url, vars),
        headers = t.headers.map { Header(it.name, VariableResolver.resolve(it.value, vars)) },
        body = when (val b = t.body) {
            null -> null
            is Body.Text -> VariableResolver.resolve(b.content, vars).toByteArray()
            is Body.FromFile -> files.read(VariableResolver.resolve(b.path, vars))
        },
    )
}
