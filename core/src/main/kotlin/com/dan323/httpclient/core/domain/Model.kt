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
    /** `# @assert <expr>` lines, in order, unparsed (see `AssertionEvaluator`). Kept apart from [settings]. */
    val assertions: List<String> = emptyList(),
)

/** A request with all variables substituted, ready to send. */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: List<Header> = emptyList(),
    val body: ByteArray? = null,
    val options: RequestOptions = RequestOptions(),
)

/** Per-request executor settings, resolved and validated from `# @setting` directives. */
data class RequestOptions(
    /** Maximum time to wait for the response (headers); body streaming is not covered. Null means no timeout. */
    val timeoutMillis: Long? = null,
    /** False disables following redirects (`@no-redirect`); true leaves the executor's own redirect policy. */
    val followRedirects: Boolean = true,
    /** HTTP proxy, or null for a direct connection. */
    val proxy: ProxyAddress? = null,
)

/** A validated HTTP proxy endpoint. */
data class ProxyAddress(val host: String, val port: Int) {
    init {
        require(host.isNotBlank()) { "Invalid @proxy value: empty host" }
        require(port in 1..65535) { "Invalid @proxy value: port $port out of range" }
    }

    override fun toString() = if (':' in host) "[$host]:$port" else "$host:$port"
}

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
