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
    fun scrub(message: String, substituted: Map<String, String>): String {
        val forms = LinkedHashMap<String, String>() // text to hide -> variable name
        for ((value, name) in substituted) {
            if (value.length < MIN_LENGTH) continue
            forms.putIfAbsent(value, name)
            val enc = URLEncoder.encode(value, Charsets.UTF_8)
            forms.putIfAbsent(enc, name)
            forms.putIfAbsent(enc.replace("+", "%20"), name)
        }
        var out = message
        // longest first so a value containing another one is hidden as a whole
        for ((text, name) in forms.entries.sortedByDescending { it.key.length }) {
            out = out.replace(text, "{{${name.trim()}}}")
        }
        return out
    }
}
