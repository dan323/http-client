package com.dan323.httpclient.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The JSON Schemas of what the tools return as `structuredContent` (the `outputSchema` of each tool). Part of what 1.0.0
 * keeps stable (docs/stability.md): fields may be added, none is removed or changes type. A refusal or failure has no
 * structured content (text only), so the schemas describe results only.
 */
internal object OutputSchemas {
    private fun type(vararg t: String): JsonObject = buildJsonObject {
        if (t.size == 1) put("type", t[0]) else putJsonArray("type") { t.forEach { add(JsonPrimitive(it)) } }
    }

    private fun obj(required: List<String>, properties: Map<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }

    private fun array(items: JsonObject): JsonObject = buildJsonObject { put("type", "array"); put("items", items) }

    private val listRequests = obj(listOf("requests"), mapOf("requests" to array(obj(
        listOf("index", "method", "url"),
        mapOf("index" to type("integer"), "name" to type("string", "null"), "method" to type("string"), "url" to type("string")),
    ))))

    private val listEnvironments = obj(listOf("environments"), mapOf("environments" to array(type("string"))))

    private val analyze = obj(listOf("diagnostics", "errors"), mapOf(
        "diagnostics" to array(obj(
            listOf("line", "column", "severity", "code", "message"),
            mapOf("line" to type("integer"), "column" to type("integer"), "severity" to type("string"), "code" to type("string"), "message" to type("string")),
        )),
        "errors" to type("integer"),
    ))

    /** One entry per request that ran; `remote` (headers and body from the server) is untrusted. */
    private val results = obj(listOf("results"), mapOf("results" to array(obj(
        listOf("request"),
        mapOf(
            "request" to type("string"),
            "status" to type("integer"),
            "error" to type("string"),
            "assertionsNotRun" to type("integer"),
            "durationMillis" to type("integer"),
            "remote" to obj(listOf("untrusted"), mapOf("untrusted" to type("boolean"), "notice" to type("string"), "headers" to array(type("string")), "body" to type("string"), "bodyTruncated" to type("string"))),
            "assertions" to array(obj(listOf("assertion", "passed", "message"), mapOf("assertion" to type("string"), "passed" to type("boolean"), "message" to type("string")))),
            "saveError" to type("string"),
        ),
    ))))

    fun of(tool: String): JsonObject = when (tool) {
        "list_requests" -> listRequests
        "list_environments" -> listEnvironments
        "analyze" -> analyze
        else -> results
    }
}
