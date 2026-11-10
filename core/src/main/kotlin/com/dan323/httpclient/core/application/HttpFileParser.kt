package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpFile
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.domain.ResponseSave
import com.dan323.httpclient.core.port.`in`.ParseHttpFile

/** A malformed `.http` file; [line] is the 1-based line number and is part of the message. */
class HttpParseException(val line: Int, reason: String) : IllegalArgumentException("Line $line: $reason")

/**
 * Parser for the JetBrains `.http` format: requests separated by `###`,
 * `# @name x` request names, `@var = value` file variables, `# @setting [value]` and `# @assert expr` directives, `//` and `#` comments, `< path` file bodies.
 */
class HttpFileParser : ParseHttpFile {
    private val nameTag = Regex("""^(?:#|//)\s*@name\s+(\S+)""")
    /** JetBrains settings this client knows of but does not implement; they warn instead of being dropped silently. */
    private val IGNORED_SETTINGS = setOf("no-auto-encoding", "connection-timeout", "no-log", "use-os-credentials")
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
                    else if (m.groupValues[1] != "name") {
                        settings[m.groupValues[1]] = m.groupValues[2].trim()
                        if (m.groupValues[1].lowercase() in IGNORED_SETTINGS) {
                            warnings += "Line ${startLine + i + 1}: '@${m.groupValues[1]}' is ignored (not supported)"
                        }
                    }
                }
                i++; continue
            }
            break
        }
        if (i >= lines.size) return null

        fun lineNo(index: Int) = startLine + index + 1
        val first = lines[i].trim()
        val (method, url) = when (val kind = HttpLineSyntax.classifyRequestLine(first)) {
            is RequestLineKind.Method -> {
                val target = HttpLineSyntax.stripHttpVersion(kind.rest)
                // `QUERY HTTP/1.1` has no URL: the version would otherwise be taken as the URL.
                if (target.isBlank() || target.trim().startsWith("HTTP/")) throw HttpParseException(lineNo(i), "request line '${kind.word}' has no URL")
                kind.word.uppercase() to target
            }
            // A bare URL means GET, as in IntelliJ.
            RequestLineKind.BareUrl -> "GET" to first.substringBefore(" HTTP/")
            is RequestLineKind.UnknownMethod -> throw HttpParseException(
                lineNo(i),
                "unknown HTTP method '${kind.word}'" + (HttpLineSyntax.suggestMethod(kind.word)?.let { " (did you mean '$it'?)" } ?: ""),
            )
            is RequestLineKind.UnsupportedProtocol -> throw HttpParseException(
                lineNo(i),
                "${kind.word.uppercase()} requests are not supported (only HTTP methods are sent)",
            )
            is RequestLineKind.MissingUrl -> throw HttpParseException(lineNo(i), "request line '${kind.word}' has no URL")
        }.let { it.first to it.second.trim() }
        i++
        // Indented `?a=1` / `&b=2` lines continue the URL.
        var fullUrl = url
        while (i < lines.size && HttpLineSyntax.isQueryContinuation(lines[i].trim())) { fullUrl += lines[i].trim(); i++ }

        val headers = mutableListOf<Header>()
        while (i < lines.size && lines[i].isNotBlank()) {
            val l = lines[i].trim()
            if (HttpLineSyntax.isResponseHandler(l) || HttpLineSyntax.isResponseRedirect(l) || HttpLineSyntax.isResponseSave(l)) break // handler without a blank line
            if (!HttpLineSyntax.isComment(l)) {
                val idx = l.indexOf(':')
                if (idx < 0 && lines[i].first().isWhitespace() && headers.isNotEmpty()) {
                    // Folded header value continues the previous header.
                    val last = headers.removeLast()
                    headers += Header(last.name, last.value + " " + l)
                    i++; continue
                }
                if (idx <= 0) throw HttpParseException(lineNo(i), "header line without ':' (expected 'Name: value'): $l")
                headers += Header(l.substring(0, idx).trim(), l.substring(idx + 1).trim())
            }
            i++
        }

        // `>> file` / `>>! file` (save the response) and `<> file` (link to the latest response) are request directives.
        // Response handlers (`> {% ... %}`, `> file`) are not supported and are ignored with a warning. All of them only
        // count at the end of the block; earlier lines starting like that are body text.
        val from = if (i < lines.size && lines[i].isNotBlank()) i else i + 1
        class Handler(val first: Int, var last: Int, val message: String, val save: HttpLineSyntax.ResponseSaveLine? = null, val link: String? = null)
        val handlers = mutableListOf<Handler>()
        var script: Handler? = null
        for (j in from until lines.size) {
            val line = lines[j]
            val t = line.trim()
            val open = script
            when {
                open != null -> { open.last = j; if (HttpLineSyntax.endsScript(line)) script = null }
                HttpLineSyntax.startsScript(t) -> {
                    val h = Handler(j, j, "Line ${lineNo(j)}: response handler script ignored (not supported)")
                    handlers += h
                    if (!HttpLineSyntax.endsScript(t.substring(t.indexOf("{%") + 2))) script = h
                }
                HttpLineSyntax.isResponseSave(t) -> handlers += Handler(j, j, "", save = HttpLineSyntax.responseSave(t))
                HttpLineSyntax.isResponseHandler(t) -> handlers += Handler(j, j, "Line ${lineNo(j)}: response handler '$t' ignored (not supported)")
                HttpLineSyntax.isResponseRedirect(t) -> handlers += Handler(j, j, "", link = HttpLineSyntax.responseLink(t))
            }
        }
        script?.let { warnings += "Line ${lineNo(it.first)}: unterminated response handler script (missing '%}')" }
        val handlerLines = handlers.flatMap { it.first..it.last }.toSet()
        val lastBody = (from until lines.size).lastOrNull { it !in handlerLines && lines[it].isNotBlank() } ?: -1
        val bodyLines = mutableListOf<String>()
        for (j in from until lines.size) if (j !in handlerLines || j < lastBody) bodyLines += lines[j]
        // Ambiguity (JetBrains has it too): `>> x` / `<> x` directly under the last body line cannot be told from body text
        // that starts that way. We keep the JetBrains reading (a directive, so existing files keep working) and, instead of
        // inventing quoting rules for paths, warn when the "path" has whitespace, which is what prose looks like. A blank
        // line before the directive removes the doubt and the warning.
        fun warnIfProse(line: Int, lastBodyLine: Int, shown: String, path: String, what: String) {
            if (line - 1 == lastBodyLine && lastBodyLine >= 0 && path.any { it.isWhitespace() }) {
                warnings += "Line ${lineNo(line)}: $shown directly after the body is read as a directive that $what; if it is part of the body, put more text after it, otherwise add a blank line before it"
            }
        }
        var saveResponse: ResponseSave? = null
        val links = mutableListOf<String>()
        for (h in handlers.filter { it.first > lastBody }) {
            when {
                h.save != null -> {
                    if (h.save.path.isEmpty()) throw HttpParseException(lineNo(h.first), "'${if (h.save.overwrite) ">>!" else ">>"}' needs a file path")
                    if (saveResponse != null) throw HttpParseException(lineNo(h.first), "only one '>>' / '>>!' response save is allowed per request")
                    saveResponse = ResponseSave(h.save.path, h.save.overwrite)
                    warnIfProse(h.first, lastBody, "'${if (h.save.overwrite) ">>!" else ">>"} ${h.save.path}'", h.save.path, "saves the response to the file '${h.save.path}'")
                }
                h.link != null -> {
                    if (h.link.isEmpty()) throw HttpParseException(lineNo(h.first), "'<>' needs a file path")
                    links += h.link
                    warnIfProse(h.first, lastBody, "'<> ${h.link}'", h.link, "links to the file '${h.link}'")
                }
                else -> warnings += h.message
            }
        }
        while (bodyLines.isNotEmpty() && bodyLines.last().isBlank()) bodyLines.removeLast()
        val body = when {
            bodyLines.isEmpty() -> null
            bodyLines.size == 1 && HttpLineSyntax.isFileReference(bodyLines.first().trim()) ->
                bodyLines.first().trim().let { t ->
                    if (t.startsWith("<@")) warnings += "Line ${lineNo(from)}: '<@' file is sent as-is; variables inside it are not substituted"
                    Body.FromFile(t.removePrefix("<").removePrefix("@").trim())
                }
            else -> Body.Text(bodyLines.joinToString("\n"))
        }
        return RequestTemplate(name, method, fullUrl, headers, body, startLine, settings, fileVariables.toMap(), assertions, saveResponse, links)
    }

    /**
     * Drops a trailing ` # comment` from an assertion (a `#` preceded by whitespace and outside quotes).
     * A bare `# @assert` yields an empty string, which the evaluator reports as "missing expression".
     */
    internal fun stripTrailingComment(text: String): String {
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
