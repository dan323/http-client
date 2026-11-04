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

/** Extracts a value from a JSON document with a JSONPath-style expression (`$.a.b[0]`); null if absent. */
fun interface JsonExtractor {
    fun extract(json: String, path: String): String?
}

/** Reads process environment variables for `{{$env.NAME}}`; null if not set. */
fun interface EnvironmentVariables {
    fun get(name: String): String?

    companion object {
        /** No variables; the default in `core` so it stays free of I/O. */
        val NONE: EnvironmentVariables = EnvironmentVariables { null }
    }
}
