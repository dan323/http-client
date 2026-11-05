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

/**
 * Reads process environment variables for `{{$env.NAME}}`. Only names the user explicitly allowed
 * are exposed; a `.http` file must not be able to read arbitrary secrets from the process.
 */
fun interface EnvironmentVariables {
    /** Value of [name], or null if it is not set. Only called when [isAllowed] is true. */
    fun get(name: String): String?

    /** Whether `.http` files may read [name]. Denies by default: implementations must opt names in. */
    fun isAllowed(name: String): Boolean = false

    /** Tells the user how to allow a variable; shown in the error for a name that is not allowed. */
    val allowHint: String get() = "allow it in the front end's environment-variable allowlist"

    companion object {
        /** Exposes nothing; the default in `core` so it stays free of I/O. */
        val NONE: EnvironmentVariables = object : EnvironmentVariables {
            override fun get(name: String): String? = null
            override fun isAllowed(name: String) = false
            override val allowHint = "this front end exposes no environment variables"
        }
    }
}
