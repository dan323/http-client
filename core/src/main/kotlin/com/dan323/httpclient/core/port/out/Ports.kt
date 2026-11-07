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

/**
 * Persists a rendered run report (JSON, JUnit XML, ...) at [path]. Implementations create missing parent folders,
 * replace an existing file and throw [java.io.IOException] when the report cannot be written.
 */
fun interface ReportWriter {
    fun write(path: java.nio.file.Path, content: String)
}

/**
 * Persists raw bytes at [path] exactly as given (no charset conversion), e.g. a response body for the CLI `--output`.
 * Same contract as [ReportWriter]: missing parent folders are created, an existing file is replaced and
 * [java.io.IOException] is thrown when the bytes cannot be written.
 */
fun interface OutputWriter {
    fun write(path: java.nio.file.Path, bytes: ByteArray)
}

/**
 * Saves a response body for a `>> path` / `>>! path` directive. [path] is relative to the .http file and already has
 * its `{{variables}}` substituted. Implementations confine it like `< path` reads, write [body] byte for byte,
 * never overwrite an existing file unless [overwrite], and return the path actually written (relative to the .http
 * file, `/` separators; without [overwrite] a numeric suffix may have been added). They throw [java.io.IOException]
 * when the path is rejected or the write fails; the message names the path and never contains [body].
 */
fun interface ResponseSaver {
    fun save(path: String, body: ByteArray, overwrite: Boolean): String
}

/**
 * Remembers the latest response of a request that has a `<> path` link (see `ResponseLinks`). [key] identifies the
 * request in its .http file. Throws [java.io.IOException] when recording fails.
 */
fun interface ResponseLinkRecorder {
    fun record(key: String, outcome: com.dan323.httpclient.core.application.RunOutcome.Success)
}
