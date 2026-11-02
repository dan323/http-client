package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.port.out.JsonExtractor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Supports the JSONPath subset used in `.http` files: `$`, `.key`, `['key']` and `[index]`. */
class KotlinxJsonExtractor : JsonExtractor {
    private val token = Regex("""\.([^.\[\]]+)|\[(\d+)]|\['([^']*)']""")

    override fun extract(json: String, path: String): String? {
        if (!path.startsWith("$")) return null
        var node: JsonElement = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return null
        var rest = path.removePrefix("$")
        while (rest.isNotEmpty()) {
            val m = token.matchAt(rest, 0) ?: return null
            node = when {
                m.groups[2] != null -> (node as? JsonArray)?.getOrNull(m.groups[2]!!.value.toInt())
                else -> (node as? JsonObject)?.get((m.groups[1] ?: m.groups[3])!!.value)
            } ?: return null
            rest = rest.substring(m.value.length)
        }
        return when (node) {
            is JsonNull -> null
            is JsonPrimitive -> node.content
            else -> node.toString()
        }
    }
}
