package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.RunReport

/**
 * Renders a [RunReport] as JUnit XML. Exactly one `testcase` per reported request, so `tests`, `failures` and
 * `errors` match the elements: all failed assertions of a request are merged into a single `failure` (one per line);
 * a request that could not complete is an `error`.
 *
 * Identity: `classname` is the suite name (the CLI passes the file name) and `name` is `"<index>. <request name>"`,
 * where the 1-based index is the request's position in the run, so duplicate or unnamed requests stay distinct and
 * stable. Prerequisites (requests run only for chaining) are omitted when they passed, since they were not asserted;
 * a failed prerequisite is kept (as an `error`) so the run does not look green. Indexes still count omitted entries.
 *
 * Note: `time` of a failed request is 0 (no response, no duration); an empty run yields `tests="0"`.
 */
object JUnitXmlReport {
    fun render(report: RunReport, suiteName: String = "httpc"): String = buildString {
        val indexed = report.entries.withIndex().filter { !(it.value.prerequisite && it.value.passed) }
        val failures = indexed.count { it.value.error == null && !it.value.passed }
        val errors = indexed.count { it.value.error != null }
        val total = indexed.sumOf { it.value.durationMillis ?: 0L }
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<testsuite name=\"${attr(suiteName)}\" tests=\"${indexed.size}\" failures=\"$failures\" ")
        append("errors=\"$errors\" skipped=\"0\" time=\"${seconds(total)}\">\n")
        for ((i, e) in indexed) {
            val head = "  <testcase classname=\"${attr(suiteName)}\" name=\"${attr("${i + 1}. ${e.name}")}\" time=\"${seconds(e.durationMillis ?: 0)}\""
            if (e.passed) { append("$head/>\n"); continue }
            append("$head>\n")
            val error = e.error
            if (error != null) {
                append("    <error message=\"${attr(error)}\" type=\"RequestError\">${escape("${e.method} ${e.url}: $error")}</error>\n")
            } else {
                val failed = e.assertions.filterNot { it.passed }
                val httpMessage = e.httpError?.let { "$it from ${e.method} ${e.url}" }
                val message = (listOfNotNull(httpMessage) + failed.map { it.message }).joinToString("\n")
                val body = (listOfNotNull(httpMessage) + failed.map { "${it.assertion} -- ${it.message}" }).joinToString("\n")
                val type = if (e.httpError != null) "HttpStatusFailure" else "AssertionFailure"
                append("    <failure message=\"${attr(message)}\" type=\"$type\">${escape(body)}</failure>\n")
            }
            append("  </testcase>\n")
        }
        append("</testsuite>\n")
    }

    private fun seconds(millis: Long) = "${millis / 1000}.${(millis % 1000).toString().padStart(3, '0')}"

    /** Escapes for element text: XML specials, CR as a character reference; drops characters illegal in XML 1.0. */
    fun escape(s: String): String = escape(s, attribute = false)

    /** Like [escape] but also keeps tab, LF and CR as character references, which survive attribute-value normalization. */
    fun escapeAttribute(s: String): String = escape(s, attribute = true)

    private fun attr(s: String) = escapeAttribute(s)

    private fun escape(s: String, attribute: Boolean): String = buildString {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '&'.code -> append("&amp;")
                cp == '<'.code -> append("&lt;")
                cp == '>'.code -> append("&gt;")
                cp == '"'.code -> append("&quot;")
                cp == '\''.code -> append("&apos;")
                cp == 0xD -> append("&#13;")
                attribute && cp == 0x9 -> append("&#9;")
                attribute && cp == 0xA -> append("&#10;")
                cp == 0x9 || cp == 0xA || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF ->
                    appendCodePoint(cp)
            }
        }
    }
}
