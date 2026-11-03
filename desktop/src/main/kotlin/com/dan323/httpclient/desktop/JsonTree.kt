package com.dan323.httpclient.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class JsonKind { OPEN, CLOSE, COLLAPSED, EMPTY, STRING, NUMBER, BOOLEAN, NULL }

/** One visible line of the JSON tree view. [path] identifies the node for collapsing. */
data class JsonRow(
    val path: String,
    val depth: Int,
    val key: String?,
    val kind: JsonKind,
    val text: String,
    val comma: Boolean,
    /** Opening or collapsed rows can be clicked to collapse or expand. */
    val toggle: Boolean = false,
    val summary: String? = null,
)

/** Parses [text] if it is a JSON object or array (the only shapes worth a tree); null otherwise. */
fun parseJsonContainer(text: String): JsonElement? =
    runCatching { Json.parseToJsonElement(text) }.getOrNull()?.takeIf { it is JsonObject || it is JsonArray }

private const val MAX_STRING = 300

/** Flattens [root] into the rows currently visible, leaving out everything under a node whose path is in [collapsed]. */
fun flattenJson(root: JsonElement, collapsed: Set<String>): List<JsonRow> =
    Flattener(collapsed).apply { emit(root, "$", 0, null, false) }.out

private class Flattener(private val collapsed: Set<String>) {
    val out = mutableListOf<JsonRow>()

    fun emit(el: JsonElement, path: String, depth: Int, key: String?, comma: Boolean) {
        when (el) {
            is JsonObject -> container(el.entries.map { it.key to it.value }, "{", "}", "keys", path, depth, key, comma) { k, _ -> "$path.$k" }
            is JsonArray -> container(el.mapIndexed { i, v -> i.toString() to v }, "[", "]", "items", path, depth, key, comma) { _, i -> "$path[$i]" }
            is JsonNull -> out += JsonRow(path, depth, key, JsonKind.NULL, "null", comma)
            is JsonPrimitive -> out += when {
                el.isString -> {
                    val quoted = el.toString()
                    JsonRow(path, depth, key, JsonKind.STRING, if (quoted.length > MAX_STRING) quoted.take(MAX_STRING) + "…\"" else quoted, comma)
                }
                el.content == "true" || el.content == "false" -> JsonRow(path, depth, key, JsonKind.BOOLEAN, el.content, comma)
                else -> JsonRow(path, depth, key, JsonKind.NUMBER, el.content, comma)
            }
        }
    }

    private fun container(
        children: List<Pair<String, JsonElement>>, open: String, close: String, word: String,
        path: String, depth: Int, key: String?, comma: Boolean, childPath: (String, Int) -> String,
    ) {
        when {
            children.isEmpty() -> out += JsonRow(path, depth, key, JsonKind.EMPTY, open + close, comma)
            path in collapsed ->
                out += JsonRow(path, depth, key, JsonKind.COLLAPSED, "$open…$close", comma, toggle = true, summary = "${children.size} $word")
            else -> {
                out += JsonRow(path, depth, key, JsonKind.OPEN, open, false, toggle = true)
                children.forEachIndexed { i, (k, v) ->
                    emit(v, childPath(k, i), depth + 1, if (open == "{") k else null, comma = i < children.lastIndex)
                }
                out += JsonRow(path, depth, null, JsonKind.CLOSE, close, comma)
            }
        }
    }
}
