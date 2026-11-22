package com.dan323.httpclient.core.application

/**
 * Hides every substituted value of a run in what is shown to someone who must not see them (the MCP server):
 * each occurrence, raw or URL-encoded, becomes `{{name}}`, in the body, in the header lines and in messages.
 * Unlike [FailureScrubber] no value is too short to hide: a short secret is replaced too, and a short value
 * that was not secret only costs readability. Best effort: a value that was hashed, split or re-encoded by the
 * server is not recognised. Values that overlap in a text are hidden as one stretch.
 *
 * [values] maps value to variable name, as [RequestSession.substitutedValues] returns it.
 */
class ResponseRedactor(values: Map<String, String>) {
    private val values: Map<String, String> = LinkedHashMap(values)
    private val automaton = FailureScrubber.automaton(this.values, minLength = 1)

    /** Length of the longest value to hide, in characters (0 when there are none). */
    val longestValue: Int get() = values.keys.maxOfOrNull { it.length } ?: 0

    /**
     * For a text that was cut at its end, so that its last characters may be the start of a value this cannot recognise:
     * the first [keep] characters, redacted, and nothing more (see [FailureScrubber.scrubPrefix]). Choose [keep] so that the
     * dropped end is longer than any piece of a value, for example `text.length - 12 * longestValue`. With a [limit] the
     * work stops once that many characters of output exist (the result may be a little longer): for a caller that cuts the
     * output anyway, so a huge text full of matches costs memory and time only up to the limit.
     */
    fun redactPrefix(text: String, keep: Int, limit: Int = Int.MAX_VALUE): String =
        automaton?.let { FailureScrubber.scrubPrefix(text, it, keep, limit) } ?: text.substring(0, minOf(keep.coerceIn(0, text.length), limit))

    fun redact(text: String): String = automaton?.let { FailureScrubber.scrub(text, it) } ?: text

    private val lowerCaseAutomaton = FailureScrubber.automaton(values, minLength = 1, lowerCase = true)

    /**
     * A header name with every value in it hidden, whatever its case: names are case-insensitive and an HTTP client may
     * change their case (the JDK's lower-cases them), so a server can name a header after a secret in any case. A name that
     * holds no value is returned as it came; one that does comes back in lower case, with the placeholders.
     */
    fun redactHeaderName(name: String): String {
        val lower = name.lowercase()
        val shown = lowerCaseAutomaton?.let { FailureScrubber.scrub(lower, it) } ?: return name
        return if (shown == lower) name else shown
    }
}
