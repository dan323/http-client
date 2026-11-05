package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpFile
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.port.`in`.ParseHttpFile

/** A malformed `.http` file; [line] is the 1-based line number and is part of the message. */
class HttpParseException(val line: Int, reason: String) : IllegalArgumentException("Line $line: $reason")

/**
 * Parser for the JetBrains `.http` format: requests separated by `###`,
 * `# @name x` request names, `@var = value` file variables, `# @setting [value]` and `# @assert expr` directives, `//` and `#` comments, `< path` file bodies.
 */
class HttpFileParser : ParseHttpFile {
    private val nameTag = Regex("""^(?:#|//)\s*@name\s+(\S+)""")
    private val settingTag = Regex("""^(?:#|//)\s*@([A-Za-z][\w-]*)(?:\s+(.*))?$""")

    override fun parse(text: String): HttpFile {
        val blocks = mutableListOf(0 to mutableListOf<String>())
        text.lines().forEachIndexed { index, line ->
            if (line.startsWith("###")) blocks += (index + 1) to mutableListOf<String>() else blocks.last().second += line
        }
        // File variables apply from their line to the end of the file; a redefinition replaces the value.
        val fileVariables = linkedMapOf<String, String>()
        val warnings = mutableListOf<String>()
        val requests = blocks.mapNotNull { (start, lines) -> parseBlock(lines, start, fileVariables, warnings) }
        return HttpFile(requests, fileVariables.toMap(), warnings)
    }

    private fun parseBlock(lines: List<String>, startLine: Int, fileVariables: MutableMap<String, String>, warnings: MutableList<String>): RequestTemplate? {
        var name: String? = null
        val settings = linkedMapOf<String, String>()
        val assertions = mutableListOf<String>()
        var i = 0
        // Leading comments / blanks, picking up @name.
        while (i < lines.size) {
            val l = lines[i].trim()
            if (l.isEmpty()) { i++; continue }
            HttpLineSyntax.fileVariable(l)?.let { fileVariables[it.groupValues[1]] = it.groupValues[2].trim(); i++; continue }
            if (HttpLineSyntax.isComment(l)) {
                nameTag.find(l)?.let { name = it.groupValues[1] }
                settingTag.matchEntire(l)?.let { m ->
                    if (m.groupValues[1] == "assert") assertions += stripTrailingComment(m.groupValues[2])
                    else if (m.groupValues[1] != "name") settings[m.groupValues[1]] = m.groupValues[2].trim()
                }
                i++; continue
            }
            break
        }
        if (i >= lines.size) return null

        fun lineNo(index: Int) = startLine + index + 1
        val first = lines[i].trim()
        val (method, url) = when (val kind = HttpLineSyntax.classifyRequestLine(first)) {
            is RequestLineKind.Method -> kind.word.uppercase() to HttpLineSyntax.stripHttpVersion(kind.rest)
            // A bare URL means GET, as in IntelliJ.
            RequestLineKind.BareUrl -> "GET" to first.substringBefore(" HTTP/")
            is RequestLineKind.UnknownMethod ->
                throw HttpParseException(lineNo(i), "unknown HTTP method '${kind.word}'")
        }
        i++

        val headers = mutableListOf<Header>()
        while (i < lines.size && lines[i].isNotBlank()) {
            val l = lines[i].trim()
            if (HttpLineSyntax.isResponseHandler(l) || HttpLineSyntax.isResponseRedirect(l)) break // handler without a blank line
            if (!HttpLineSyntax.isComment(l)) {
                val idx = l.indexOf(':')
                if (idx <= 0) throw HttpParseException(lineNo(i), "header line without ':' (expected 'Name: value'): $l")
                headers += Header(l.substring(0, idx).trim(), l.substring(idx + 1).trim())
            }
            i++
        }

        // Response handlers (`> {% ... %}`, `> file`) and `<> file` redirects are not supported: warn and keep them out of the body.
        val bodyLines = mutableListOf<String>()
        var inScript = false
        for (j in (if (i < lines.size && lines[i].isNotBlank()) i else i + 1) until lines.size) {
            val line = lines[j]
            val t = line.trim()
            when {
                inScript -> if (HttpLineSyntax.endsScript(line)) inScript = false
                HttpLineSyntax.startsScript(t) -> {
                    warnings += "Line ${lineNo(j)}: response handler script ignored (not supported)"
                    inScript = !HttpLineSyntax.endsScript(t.substring(t.indexOf("{%") + 2))
                }
                HttpLineSyntax.isResponseHandler(t) -> warnings += "Line ${lineNo(j)}: response handler '$t' ignored (not supported)"
                HttpLineSyntax.isResponseRedirect(t) -> warnings += "Line ${lineNo(j)}: response redirect '$t' ignored (not supported)"
                else -> bodyLines += line
            }
        }
        while (bodyLines.isNotEmpty() && bodyLines.last().isBlank()) bodyLines.removeLast()
        val body = when {
            bodyLines.isEmpty() -> null
            bodyLines.size == 1 && HttpLineSyntax.isFileReference(bodyLines.first().trim()) ->
                Body.FromFile(bodyLines.first().trim().removePrefix("<").trim())
            else -> Body.Text(bodyLines.joinToString("\n"))
        }
        return RequestTemplate(name, method, url, headers, body, startLine, settings, fileVariables.toMap(), assertions)
    }

    /**
     * Drops a trailing ` # comment` from an assertion (a `#` preceded by whitespace and outside quotes).
     * A bare `# @assert` yields an empty string, which the evaluator reports as "missing expression".
     */
    private fun stripTrailingComment(text: String): String {
        var quote: Char? = null
        for ((i, c) in text.withIndex()) {
            when {
                quote != null -> if (c == quote) quote = null
                (c == '"' || c == '\'') && (i == 0 || text[i - 1].isWhitespace()) -> quote = c
                c == '#' && (i == 0 || text[i - 1].isWhitespace()) -> return text.substring(0, i).trim()
            }
        }
        return text.trim()
    }
}
