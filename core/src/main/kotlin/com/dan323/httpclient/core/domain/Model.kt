package com.dan323.httpclient.core.domain

data class Header(val name: String, val value: String)

sealed interface Body {
    data class Text(val content: String) : Body
    /** Body loaded from a file, written `< ./path` in a .http file. */
    data class FromFile(val path: String) : Body
}

/** A request as written in a .http file; may still contain `{{variables}}`. */
data class RequestTemplate(
    val name: String?,
    val method: String,
    val url: String,
    val headers: List<Header> = emptyList(),
    val body: Body? = null,
    /** 0-based line in the source where this request's block begins (after the preceding `###`). */
    val startLine: Int = 0,
    /** `# @setting [value]` directives other than `@name`, e.g. `timeout` to `5` or `no-redirect` to `""`. */
    val settings: Map<String, String> = emptyMap(),
    /** File variables (`@var = value`) defined at or before this request; they override environment values. */
    val fileVariables: Map<String, String> = emptyMap(),
)

/** A request with all variables substituted, ready to send. */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: List<Header> = emptyList(),
    val body: ByteArray? = null,
)

data class HttpResponse(
    val statusCode: Int,
    val headers: List<Header>,
    val body: ByteArray,
    val durationMillis: Long,
) {
    fun header(name: String): String? = headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
    fun bodyAsString(): String = body.toString(Charsets.UTF_8)
}

data class HttpFile(val requests: List<RequestTemplate>, val variables: Map<String, String> = emptyMap())

/** Named variable set, e.g. `dev` from http-client.env.json. */
data class Environment(val name: String, val variables: Map<String, String>)
