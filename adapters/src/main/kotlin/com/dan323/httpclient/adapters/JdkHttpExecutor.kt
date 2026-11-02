package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.HttpExecutor
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

class JdkHttpExecutor(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : HttpExecutor {
    // Restricted by java.net.http; the JDK sets these itself.
    private val restricted = setOf("host", "content-length", "connection", "expect", "upgrade")

    override fun execute(request: HttpRequest): HttpResponse {
        val builder = java.net.http.HttpRequest.newBuilder(URI.create(request.url))
            .method(request.method, request.body?.let(BodyPublishers::ofByteArray) ?: BodyPublishers.noBody())
        request.headers.filter { it.name.lowercase() !in restricted }.forEach { builder.header(it.name, it.value) }

        val start = System.nanoTime()
        val resp = client.send(builder.build(), BodyHandlers.ofByteArray())
        val ms = (System.nanoTime() - start) / 1_000_000
        val headers = resp.headers().map().flatMap { (k, vs) -> vs.map { Header(k, it) } }
        return HttpResponse(resp.statusCode(), headers, resp.body(), ms)
    }
}
