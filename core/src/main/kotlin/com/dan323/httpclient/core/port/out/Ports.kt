package com.dan323.httpclient.core.port.out

import com.dan323.httpclient.core.domain.Environment
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse

/** Sends a request over the wire. */
fun interface HttpExecutor {
    fun execute(request: HttpRequest): HttpResponse
}

/** Reads file contents referenced by `< path` bodies, relative to the .http file. */
fun interface FileReader {
    fun read(path: String): ByteArray
}

/** Supplies environments (e.g. from http-client.env.json). */
fun interface EnvironmentRepository {
    fun load(): List<Environment>
}
