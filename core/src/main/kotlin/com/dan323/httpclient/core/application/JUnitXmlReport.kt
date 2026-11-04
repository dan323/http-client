package com.dan323.httpclient.core.application

/**
 * Renders a [RunReport] as JUnit XML: one `testcase` per request; a failed assertion is a `failure`,
 * a request that could not complete is an `error`.
 */
object JUnitXmlReport {
    fun render(report: RunReport, suiteName: String = "httpc"): String = buildString {
        val failures = report.entries.count { it.error == null && !it.passed }
        val errors = report.entries.count { it.error != null }
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<testsuite name=\"${escape(suiteName)}\" tests=\"${report.requests}\" failures=\"$failures\" ")
        append("errors=\"$errors\" skipped=\"0\" time=\"${seconds(report.totalDurationMillis)}\">\n")
        for (e in report.entries) {
            val head = "  <testcase classname=\"${escape(suiteName)}\" name=\"${escape(e.name)}\" time=\"${seconds(e.durationMillis ?: 0)}\""
            if (e.passed) { append("$head/>\n"); continue }
            append("$head>\n")
            if (e.error != null) {
                append("    <error message=\"${escape(e.error)}\" type=\"RequestError\">${escape("${e.method} ${e.url}: ${e.error}")}</error>\n")
            }
            for (a in e.assertions.filterNot { it.passed }) {
                append("    <failure message=\"${escape(a.message)}\" type=\"AssertionFailure\">${escape("${a.assertion} -- ${a.message}")}</failure>\n")
            }
            append("  </testcase>\n")
        }
        append("</testsuite>\n")
    }

    private fun seconds(millis: Long) = "${millis / 1000}.${(millis % 1000).toString().padStart(3, '0')}"

    /** Escapes XML special characters and drops characters that are illegal in XML 1.0. */
    fun escape(s: String): String = buildString {
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
                cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF ->
                    appendCodePoint(cp)
            }
        }
    }
}
