package com.dan323.httpclient.adapters

import kotlinx.serialization.json.Json

private val pretty = Json { prettyPrint = true }

/** Re-indents [text] if it is a JSON object or array; null otherwise. */
fun prettyJson(text: String): String? {
    val t = text.trim()
    if (!t.startsWith("{") && !t.startsWith("[")) return null
    return runCatching { pretty.encodeToString(Json.parseToJsonElement(t)) }.getOrNull()
}
