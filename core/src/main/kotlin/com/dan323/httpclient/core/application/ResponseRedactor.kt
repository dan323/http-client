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
