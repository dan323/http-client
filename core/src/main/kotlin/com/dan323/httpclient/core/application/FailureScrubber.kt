package com.dan323.httpclient.core.application

import java.net.URLEncoder

/**
 * Keeps substituted variable values (tokens, passwords) out of failure messages: exception texts such as
 * `URI.create` errors echo the whole resolved URL. Each occurrence of a substituted value, raw or
 * URL-encoded, is replaced by `{{name}}` so the message still says which variable was involved.
 * Values shorter than [MIN_LENGTH] characters are left alone (replacing them would only garble the text).
 */
internal object FailureScrubber {
    const val MIN_LENGTH = 3

    /** [substituted] maps value to the variable name it came from. */
    fun scrub(message: String, substituted: Map<String, String>, minLength: Int = MIN_LENGTH): String {
        val (forms, regex) = compile(substituted, minLength) ?: return message
        return regex.replace(message) { "{{${forms.getValue(it.value).trim()}}}" }
    }

    /**
     * The first [keep] characters of [text], scrubbed, and nothing after them: a value that starts inside them is hidden
     * whole even when it runs past [keep], and one that starts at or after [keep] is not shown. The result therefore never
     * holds a piece of a value cut by the edge, and, unlike scrubbing and then cutting, does not depend on how long the
     * placeholders are.
     */
    fun scrubPrefix(text: String, substituted: Map<String, String>, keep: Int, minLength: Int = MIN_LENGTH): String {
        val end = keep.coerceIn(0, text.length)
        val (forms, regex) = compile(substituted, minLength) ?: return text.substring(0, end)
        val out = StringBuilder()
        var at = 0
        for (m in regex.findAll(text)) {
            if (m.range.first >= end) break
            out.append(text, at, m.range.first).append("{{").append(forms.getValue(m.value).trim()).append("}}")
            at = m.range.last + 1
        }
        if (at < end) out.append(text, at, end)
        return out.toString()
    }

    private fun compile(substituted: Map<String, String>, minLength: Int): Pair<Map<String, String>, Regex>? {
        val forms = LinkedHashMap<String, String>() // text to hide -> variable name
        for ((value, name) in substituted) {
            if (value.isEmpty() || value.length < minLength) continue
            forms.putIfAbsent(value, name)
            val enc = URLEncoder.encode(value, Charsets.UTF_8)
            forms.putIfAbsent(enc, name)
            forms.putIfAbsent(enc.replace("+", "%20"), name)
        }
        if (forms.isEmpty()) return null
        // one pass, longest alternative first: a value containing another one is hidden as a whole and the
        // inserted `{{name}}` placeholders are never scrubbed again
        return forms to Regex(forms.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) })
    }
}
