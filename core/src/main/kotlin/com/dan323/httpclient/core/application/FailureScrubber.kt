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
    fun scrubPrefix(text: String, substituted: Map<String, String>, keep: Int, minLength: Int = MIN_LENGTH, limit: Int = Int.MAX_VALUE): String {
        val end = keep.coerceIn(0, text.length)
        val c = compile(substituted, minLength) ?: return text.substring(0, if (end <= limit) end else limit)
        val out = StringBuilder()
        var at = 0
        for (g in groups(text, c)) {
            if (g.start >= end) break
            // enough output for the caller (who cuts it anyway): stop before the rest of a large text is scanned
            val room = limit - out.length
            if (g.start - at >= room) return out.append(text, at, at + room).toString()
            out.append(text, at, g.start).append(g.placeholder())
            at = g.end
            if (out.length >= limit) return out.toString()
        }
        if (at < end) out.append(text, at, if (end - at <= limit - out.length) end else at + (limit - out.length))
        return out.toString()
    }

    private class Compiled(val names: Map<String, String>, val regex: Regex)

    /** A stretch of text to hide: occurrences that overlap are one group, so no piece of any of them is left showing. */
    private class Group(val start: Int, var end: Int, firstName: String) {
        /** The names in the order they were first seen, each once: a group that grows by a million overlapping matches of one value stays one name. */
        val names = LinkedHashSet<String>().apply { add(firstName) }

        fun placeholder(): String = names.joinToString("") { "{{$it}}" }
    }

    /**
     * Every occurrence of every form, overlapping ones included (the regex looks ahead at each position, so a match never
     * consumes text that another occurrence needs), merged into groups. An occurrence inside the stretch already hidden adds
     * no name, so a value found inside a longer one is reported as the longer one, as before.
     */
    private fun groups(text: String, c: Compiled): Sequence<Group> = sequence {
        // one group at a time: a long text full of matches is never held in memory as a list of them
        var current: Group? = null
        for (m in c.regex.findAll(text)) {
            val start = m.range.first
            val form = m.groupValues[1]
            val end = start + form.length
            val open = current
            if (open != null && start < open.end) {
                if (end > open.end) { open.end = end; open.names.add(c.names.getValue(form).trim()) }
            } else {
                if (open != null) yield(open)
                current = Group(start, end, c.names.getValue(form).trim())
            }
        }
        current?.let { yield(it) }
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
