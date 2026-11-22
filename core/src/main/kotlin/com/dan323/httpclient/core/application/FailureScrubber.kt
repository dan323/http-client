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
        val c = compile(substituted, minLength) ?: return message
        val out = StringBuilder()
        var at = 0
        for (g in groups(message, c)) {
            out.append(message, at, g.start).append(g.placeholder())
            at = g.end
        }
        return out.append(message, at, message.length).toString()
    }

    /**
     * The first [keep] characters of [text], scrubbed, and nothing after them: a value that starts inside them is hidden
     * whole even when it runs past [keep], and one that starts at or after [keep] is not shown. The result therefore never
     * holds a piece of a value cut by the edge, and, unlike scrubbing and then cutting, does not depend on how long the
     * placeholders are.
     */
    fun scrubPrefix(text: String, substituted: Map<String, String>, keep: Int, minLength: Int = MIN_LENGTH): String {
        val end = keep.coerceIn(0, text.length)
        val c = compile(substituted, minLength) ?: return text.substring(0, end)
        val out = StringBuilder()
        var at = 0
        for (g in groups(text, c)) {
            if (g.start >= end) break
            out.append(text, at, g.start).append(g.placeholder())
            at = g.end
        }
        if (at < end) out.append(text, at, end)
        return out.toString()
    }

    private class Compiled(val names: Map<String, String>, val regex: Regex)

    /** A stretch of text to hide: occurrences that overlap are one group, so no piece of any of them is left showing. */
    private class Group(val start: Int, var end: Int, val names: MutableList<String>) {
        fun placeholder(): String = names.distinct().joinToString("") { "{{$it}}" }
    }

    /**
     * Every occurrence of every form, overlapping ones included (the regex looks ahead at each position, so a match never
     * consumes text that another occurrence needs), merged into groups. An occurrence inside the stretch already hidden adds
     * no name, so a value found inside a longer one is reported as the longer one, as before.
     */
    private fun groups(text: String, c: Compiled): List<Group> {
        val groups = ArrayList<Group>()
        for (m in c.regex.findAll(text)) {
            val start = m.range.first
            val form = m.groupValues[1]
            val end = start + form.length
            val last = groups.lastOrNull()
            if (last != null && start < last.end) {
                if (end > last.end) { last.end = end; last.names += c.names.getValue(form).trim() }
            } else {
                groups += Group(start, end, mutableListOf(c.names.getValue(form).trim()))
            }
        }
        return groups
    }

    private fun compile(substituted: Map<String, String>, minLength: Int): Compiled? {
        val forms = LinkedHashMap<String, String>() // text to hide -> variable name
        for ((value, name) in substituted) {
            if (value.isEmpty() || value.length < minLength) continue
            forms.putIfAbsent(value, name)
            val enc = URLEncoder.encode(value, Charsets.UTF_8)
            forms.putIfAbsent(enc, name)
            forms.putIfAbsent(enc.replace("+", "%20"), name)
        }
        if (forms.isEmpty()) return null
        // longest alternative first, inside a lookahead: at every position the longest form that starts there is found
        // without using up the text, so overlapping occurrences are all seen
        return Compiled(forms, Regex("(?=(" + forms.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + "))"))
    }
}
