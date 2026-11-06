package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.RequestTemplate
import kotlinx.serialization.json.Json

private val prettyPrinter = Json { prettyPrint = true }

/** Re-indents [text] if it is a JSON object or array; null otherwise. */
fun prettyJsonOrNull(text: String): String? {
    val element = parseJsonContainer(text.trim()) ?: return null
    return runCatching { prettyPrinter.encodeToString(element) }.getOrNull()
}

/** [text] re-indented when it is a JSON object or array, unchanged otherwise. */
fun prettyOrRaw(text: String): String = prettyJsonOrNull(text) ?: text

/** `name  ·  METHOD url` for a named request, `METHOD url` otherwise. */
fun requestSummary(r: RequestTemplate): String = r.name?.let { "$it  ·  ${r.method} ${r.url}" } ?: "${r.method} ${r.url}"

/** Display form of one [RunOutcome]; UI modules only choose toolkit colours for [StatusClass]. */
sealed interface OutcomeDisplay {
    /** The request's `@name`, or `METHOD url`. */
    val title: String

    /** [requestSummary] of the request. */
    val summary: String

    data class Response(
        override val title: String,
        override val summary: String,
        val status: Int,
        val statusClass: StatusClass,
        /** `200 OK`; just the number when the reason phrase is unknown. */
        val statusLabel: String,
        val durationLabel: String,
        val sizeLabel: String,
        val contentType: String?,
        val headers: List<Pair<String, String>>,
        /** The body as received (UTF-8). */
        val rawBody: String,
        /** [rawBody] re-indented when it is a JSON object or array, else equal to [rawBody]. */
        val prettyBody: String,
        /** True when the body is a JSON object or array (so pretty/raw and the tree view make sense). */
        val isJson: Boolean,
        /** `Saved to out/a.json` after a successful `>>` / `>>!` (the path is relative to the .http file); null otherwise. */
        val savedLabel: String? = null,
        /** The `>>` / `>>!` save failed (the response itself is fine), e.g. `Cannot save response to 'x': ...`; null otherwise. */
        val saveError: String? = null,
        /** Keeping the `<>` link failed, e.g. `Cannot keep the response link: ...`; null otherwise. */
        val linkError: String? = null,
    ) : OutcomeDisplay {
        /** Save/link feedback lines for the response pane, in display order; each is flagged as an error or not. */
        val notices: List<Notice>
            get() = listOfNotNull(
                savedLabel?.let { Notice(it, isError = false) },
                saveError?.let { Notice(it, isError = true) },
                linkError?.let { Notice(it, isError = true) },
            )
    }

    /** One line about saving the response; UI modules only choose the colour for [isError]. */
    data class Notice(val text: String, val isError: Boolean)

    data class Error(override val title: String, override val summary: String, val message: String) : OutcomeDisplay

    /** A request whose stored response from earlier in the session was used instead of sending it again. */
    data class Reused(override val title: String, override val summary: String) : OutcomeDisplay
}

fun reusedDisplay(r: RequestTemplate): OutcomeDisplay.Reused = OutcomeDisplay.Reused(requestTitle(r), requestSummary(r))

private fun requestTitle(r: RequestTemplate) = r.name ?: "${r.method} ${r.url}"

fun RunOutcome.display(): OutcomeDisplay = when (this) {
    is RunOutcome.Failure -> OutcomeDisplay.Error(requestTitle(request), requestSummary(request), message)
    is RunOutcome.Success -> {
        val body = response.bodyAsString()
        val pretty = prettyJsonOrNull(body)
        OutcomeDisplay.Response(
            title = requestTitle(request),
            summary = requestSummary(request),
            status = response.statusCode,
            statusClass = statusClass(response.statusCode),
            statusLabel = "${response.statusCode} ${statusText(response.statusCode)}".trim(),
            durationLabel = durationLabel(response.durationMillis),
            sizeLabel = sizeLabel(response.body.size),
            contentType = response.header("content-type"),
            headers = response.headers.map { it.name to it.value },
            rawBody = body,
            prettyBody = pretty ?: body,
            isJson = pretty != null,
            savedLabel = savedTo?.let { "Saved to $it" },
            saveError = saveError,
            linkError = linkError,
        )
    }
}
