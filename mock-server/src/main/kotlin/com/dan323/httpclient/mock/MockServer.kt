package com.dan323.httpclient.mock

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * A tiny subset of httpbin.org: echo endpoints (`/get`, `/post`, `/put`, `/patch`, `/delete`, `/anything[/...]`),
 * `/headers`, `/status/{code}`, `/redirect/{n}` and `/bearer`. Pass port 0 to pick a free port.
 */
private val PRETTY_JSON = Json { prettyPrint = true }

class MockServer(port: Int = 8080) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    val port get() = server.address.port

    private val methodsByPath = mapOf(
        "/get" to setOf("GET", "HEAD", "OPTIONS"),
        "/post" to setOf("POST", "OPTIONS"),
        "/put" to setOf("PUT", "OPTIONS"),
        "/patch" to setOf("PATCH", "OPTIONS"),
        "/delete" to setOf("DELETE", "OPTIONS"),
    )

    init {
        server.createContext("/") { ex -> ex.use { route(it) } }
    }

    fun start() = apply { server.start() }
    fun stop() = server.stop(0)

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path
        val method = ex.requestMethod
        val segments = path.trim('/').split('/')
        when {
            path in methodsByPath -> {
                val allowed = methodsByPath.getValue(path)
                when {
                    method == "OPTIONS" -> reply(ex, 200, null, mapOf("Allow" to allowed.joinToString(", ")))
                    method in allowed -> reply(ex, 200, echo(ex))
                    else -> reply(ex, 405, null)
                }
            }
            segments[0] == "anything" -> reply(ex, 200, echo(ex))
            path == "/headers" -> reply(ex, 200, buildJsonObject { put("headers", headers(ex)) })
            segments[0] == "status" && segments.size == 2 && segments[1].toIntOrNull() in 200..599 ->
                reply(ex, segments[1].toInt(), null)
            segments[0] == "redirect" && segments.size == 2 && segments[1].toIntOrNull()?.let { it >= 1 } == true -> {
                val n = segments[1].toInt()
                reply(ex, 302, null, mapOf("Location" to if (n > 1) "/redirect/${n - 1}" else "/get"))
            }
            path == "/bearer" -> {
                val token = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }
                if (token == null) reply(ex, 401, null, mapOf("WWW-Authenticate" to "Bearer"))
                else reply(ex, 200, buildJsonObject { put("authenticated", true); put("token", token) })
            }
            else -> reply(ex, 404, buildJsonObject { put("error", "no such mock endpoint: $path") })
        }
    }

    private fun headers(ex: HttpExchange) = JsonObject(
        ex.requestHeaders.entries.associate { (k, v) -> k to JsonPrimitive(v.joinToString(", ")) },
    )

    private fun echo(ex: HttpExchange): JsonObject {
        val raw = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val contentType = ex.requestHeaders.getFirst("Content-Type").orEmpty()
        val multipart = multipartParts(raw, contentType)
        return buildJsonObject {
            put("method", ex.requestMethod)
            put("url", "http://${ex.requestHeaders.getFirst("Host")}${ex.requestURI}")
            put("args", pairs(ex.requestURI.rawQuery))
            put("headers", headers(ex))
            put("data", raw)
            put("form", if (contentType.startsWith("application/x-www-form-urlencoded")) pairs(raw) else JsonObject(multipart.fields))
            put("files", JsonObject(multipart.files))
            put("json", if (raw.isBlank()) JsonNull else runCatching { Json.parseToJsonElement(raw) }.getOrDefault(JsonNull))
        }
    }

    private class Multipart(val fields: Map<String, JsonElement>, val files: Map<String, JsonElement>)

    /** httpbin-style view of a `multipart/form-data` body: text parts as `form`, parts with a filename as `files`. */
    private fun multipartParts(raw: String, contentType: String): Multipart {
        val boundary = Regex("""boundary=(?:"([^"]+)"|([^;\s]+))""", RegexOption.IGNORE_CASE).find(contentType)?.let { it.groups[1]?.value ?: it.groups[2]?.value }
        if (boundary == null || !contentType.startsWith("multipart/form-data", ignoreCase = true)) return Multipart(emptyMap(), emptyMap())
        val fields = linkedMapOf<String, JsonElement>()
        val files = linkedMapOf<String, JsonElement>()
        for (segment in raw.split(Regex("(?:^|\r\n)--" + Regex.escape(boundary))).drop(1)) {
            if (segment.startsWith("--")) break
            val (head, content) = segment.removePrefix("\r\n").split("\r\n\r\n", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val disposition = head.lineSequence().firstOrNull { it.startsWith("Content-Disposition", ignoreCase = true) } ?: continue
            val name = Regex("""[; ]name="([^"]*)"""").find(disposition)?.groupValues?.get(1) ?: continue
            val value = JsonPrimitive(content.removeSuffix("\r\n"))
            if (Regex("""filename="""").containsMatchIn(disposition)) files[name] = value else fields[name] = value
        }
        return Multipart(fields, files)
    }

    private fun pairs(query: String?): JsonObject = JsonObject(
        query.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
            URLDecoder.decode(k, Charsets.UTF_8) to JsonPrimitive(URLDecoder.decode(v, Charsets.UTF_8))
        },
    )

    private fun reply(ex: HttpExchange, status: Int, body: JsonElement?, headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        val bytes = body?.let { PRETTY_JSON.encodeToString(JsonElement.serializer(), it).toByteArray() }
        if (bytes != null) ex.responseHeaders.add("Content-Type", "application/json")
        val noBody = bytes == null || ex.requestMethod == "HEAD" || status == 204
        ex.sendResponseHeaders(status, if (noBody) -1 else bytes!!.size.toLong())
        if (!noBody) ex.responseBody.write(bytes!!)
    }
}
