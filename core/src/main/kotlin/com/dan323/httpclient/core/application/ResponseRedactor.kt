package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpResponse

/**
 * Hides every substituted value of a run in what is shown to someone who must not see them (the MCP server):
 * each occurrence, raw or URL-encoded, becomes `{{name}}`, in the body, in the header values and in messages.
 * Unlike [FailureScrubber] no value is too short to hide: a short secret is replaced too, and a short value
 * that was not secret only costs readability. Best effort: a value that was hashed, split or re-encoded by the
 * server is not recognised.
 *
 * [values] maps value to variable name, as [RequestSession.substitutedValues] returns it.
 */
class ResponseRedactor(values: Map<String, String>) {
    private val values: Map<String, String> = LinkedHashMap(values)

    /** Length of the longest value to hide, in characters (0 when there are none). */
    val longestValue: Int get() = values.keys.maxOfOrNull { it.length } ?: 0

    /**
     * For a text that was cut at its end, so that its last characters may be the start of a value this cannot recognise:
     * the first [keep] characters, redacted, and nothing more (see [FailureScrubber.scrubPrefix]). Choose [keep] so that the
     * dropped end is longer than any piece of a value, for example `text.length - 12 * longestValue`. With a [limit] the
     * work stops once that many characters of output exist (the result may be a little longer): for a caller that cuts the
     * output anyway, so a huge text full of matches costs memory and time only up to the limit.
     */
    fun redactPrefix(text: String, keep: Int, limit: Int = Int.MAX_VALUE): String = FailureScrubber.scrubPrefix(text, values, keep, minLength = 1, limit = limit)

    fun redact(text: String): String = FailureScrubber.scrub(text, values, minLength = 1)

    fun redact(response: HttpResponse): HttpResponse {
        val text = response.bodyAsString()
        val hidden = redact(text)
        return response.copy(
            headers = response.headers.map { Header(it.name, redact(it.value)) },
            // bytes untouched when nothing was hidden, so a binary body is not mangled by decoding
            body = if (hidden == text) response.body else hidden.toByteArray(Charsets.UTF_8),
        )
    }
}
