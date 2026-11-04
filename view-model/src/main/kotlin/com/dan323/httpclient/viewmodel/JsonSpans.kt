package com.dan323.httpclient.viewmodel

enum class JsonPart { KEY, STRING, NUMBER, KEYWORD, PUNCT }

/** A coloured span `[start, end)` of a pretty-printed JSON text; unlisted characters are plain. */
data class JsonSpan(val part: JsonPart, val start: Int, val end: Int)

/** Cheap tokenizer for already-formatted JSON; never fails (unknown text is left uncoloured). */
fun jsonSpans(text: String): List<JsonSpan> {
    val out = mutableListOf<JsonSpan>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '"' -> {
                var j = i + 1
                while (j < text.length && text[j] != '"') j += if (text[j] == '\\') 2 else 1
                val end = minOf(j + 1, text.length)
                var k = end
                while (k < text.length && text[k] == ' ') k++
                out += JsonSpan(if (k < text.length && text[k] == ':') JsonPart.KEY else JsonPart.STRING, i, end)
                i = end
            }
            c == '-' || c.isDigit() -> {
                var j = i + 1
                while (j < text.length && (text[j].isDigit() || text[j] in ".eE+-")) j++
                out += JsonSpan(JsonPart.NUMBER, i, j); i = j
            }
            text.startsWith("true", i) || text.startsWith("null", i) -> { out += JsonSpan(JsonPart.KEYWORD, i, i + 4); i += 4 }
            text.startsWith("false", i) -> { out += JsonSpan(JsonPart.KEYWORD, i, i + 5); i += 5 }
            c in "{}[],:" -> { out += JsonSpan(JsonPart.PUNCT, i, i + 1); i++ }
            else -> i++
        }
    }
    return out
}
