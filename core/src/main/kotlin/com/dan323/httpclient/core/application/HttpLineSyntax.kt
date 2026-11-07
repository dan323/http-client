package com.dan323.httpclient.core.application

/** How the first line of a request reads. Shared by [HttpFileParser] and [HttpLineLexer] so they always agree. */
sealed interface RequestLineKind {
    /** `METHOD rest`; [word] is the method as written, [rest] the trimmed remainder (URL and optional HTTP version). */
    data class Method(val word: String, val rest: String) : RequestLineKind
    /** A bare URL, which means GET. */
    data object BareUrl : RequestLineKind
    /** `WORD URL` where WORD is not an HTTP method (a typo such as `GTE`). */
    data class UnknownMethod(val word: String) : RequestLineKind
    /** A lone known method word with no URL (`GET`). */
    data class MissingUrl(val word: String) : RequestLineKind
}

/** Line classification shared by the parser and the lexer. */
object HttpLineSyntax {
    val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT", "GRAPHQL", "WEBSOCKET", "GRPC")

    private val fileVariable = Regex("""^@([^\s=]+)\s*=\s*(.*)$""")
    private val httpVersion = Regex("""\s+HTTP/\S+$""")
    private val letters = Regex("""[A-Za-z]+""")

    /** Matches a trimmed `@name = value` line: group 1 is the name, group 2 the value. */
    fun fileVariable(trimmed: String): MatchResult? = fileVariable.matchEntire(trimmed)

    fun isComment(trimmed: String) = trimmed.startsWith("#") || trimmed.startsWith("//")

    /** Removes a trailing ` HTTP/1.1`-style version from a request line remainder. */
    fun stripHttpVersion(text: String) = text.replace(httpVersion, "")

    fun classifyRequestLine(line: String): RequestLineKind {
        val t = line.trim()
        val ws = t.indexOfFirst { it.isWhitespace() }
        if (ws < 0) return if (t.uppercase() in METHODS) RequestLineKind.MissingUrl(t) else RequestLineKind.BareUrl
        val word = t.substring(0, ws)
        val rest = t.substring(ws).trim()
        return when {
            word.uppercase() in METHODS -> RequestLineKind.Method(word, rest)
            letters.matches(word) && stripHttpVersion(rest).isNotBlank() -> RequestLineKind.UnknownMethod(word)
            else -> RequestLineKind.BareUrl
        }
    }

    /** The known method one edit away from [word] (a likely typo), if any. */
    fun suggestMethod(word: String): String? = METHODS.firstOrNull { editDistanceAtMostOne(word.uppercase(), it) }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (a == b || kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return when {
            a.length == b.length -> a.substring(i + 1) == b.substring(i + 1) || // substitution
                (i + 1 < a.length && a[i] == b[i + 1] && a[i + 1] == b[i] && a.substring(i + 2) == b.substring(i + 2)) // swap
            a.length > b.length -> a.substring(i + 1) == b.substring(i)
            else -> b.substring(i + 1) == a.substring(i)
        }
    }

    /** `< path` or `<@ path` (with a space) is a file reference; `<html>` is body text. */
    fun isFileReference(trimmed: String) = trimmed.startsWith("< ") || trimmed.startsWith("<@ ")

    /** Query continuation line (`?a=1`, `&b=2`) that extends the request URL. */
    fun isQueryContinuation(trimmed: String) = trimmed.startsWith("?") || trimmed.startsWith("&")

    private val saveLine = Regex("""^>>(!?)(?:\s+(.*))?$""")
    private val linkLine = Regex("""^<>(?:\s+(.*))?$""")

    /** A `>> path` / `>>! path` line; [path] is trimmed and may be empty (an error the parser reports). */
    class ResponseSaveLine(val overwrite: Boolean, val path: String)

    /** `>> path` (never overwrites) or `>>! path` (overwrites): the saved-response directive, else null. `>>path` is text. */
    fun responseSave(trimmed: String): ResponseSaveLine? =
        saveLine.matchEntire(trimmed)?.let { ResponseSaveLine(it.groupValues[1] == "!", it.groupValues[2].trim()) }

    fun isResponseSave(trimmed: String) = saveLine.matches(trimmed)

    /** The path of a `<> path` response-link line (empty for a bare `<>`), else null. */
    fun responseLink(trimmed: String): String? = linkLine.matchEntire(trimmed)?.groupValues?.get(1)?.trim()

    /** `<> file` response-link line. */
    fun isResponseRedirect(trimmed: String) = linkLine.matches(trimmed)

    /** `> {% ... %}` script start or `> file` response handler. */
    fun isResponseHandler(trimmed: String) = trimmed.startsWith("> ") || trimmed == ">"

    fun startsScript(trimmed: String) = trimmed.startsWith(">") && trimmed.substring(1).trimStart().startsWith("{%")

    /** True when the line has a `%}` outside a quoted string (quotes are tracked per line). */
    fun endsScript(line: String): Boolean {
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null -> if (c == '\\') i++ else if (c == quote) quote = null
                c == '"' || c == '\'' || c == '`' -> quote = c
                c == '%' && line.startsWith("%}", i) -> return true
            }
            i++
        }
        return false
    }
}
