package com.dan323.httpclient.core.application

/** How the first line of a request reads. Shared by [HttpFileParser] and [HttpLineLexer] so they always agree. */
sealed interface RequestLineKind {
    /** `METHOD rest`; [word] is the method as written, [rest] the trimmed remainder (URL and optional HTTP version). */
    data class Method(val word: String, val rest: String) : RequestLineKind
    /** A bare URL, which means GET. */
    data object BareUrl : RequestLineKind
    /** `WORD URL` where WORD is not an HTTP method (a typo such as `GTE`). */
    data class UnknownMethod(val word: String) : RequestLineKind
}

/** Line classification shared by the parser and the lexer. */
object HttpLineSyntax {
    val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT")

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
        if (ws < 0) return RequestLineKind.BareUrl
        val word = t.substring(0, ws)
        val rest = t.substring(ws).trim()
        return when {
            word.uppercase() in METHODS -> RequestLineKind.Method(word, rest)
            letters.matches(word) && stripHttpVersion(rest).isNotBlank() -> RequestLineKind.UnknownMethod(word)
            else -> RequestLineKind.BareUrl
        }
    }

    /** `< path` (with a space) is a file reference; `<html>` is body text. */
    fun isFileReference(trimmed: String) = trimmed.startsWith("< ")

    /** `<> file` response-redirect line. */
    fun isResponseRedirect(trimmed: String) = trimmed.startsWith("<> ")

    /** `> {% ... %}` script start or `> file` response handler. */
    fun isResponseHandler(trimmed: String) = trimmed.startsWith("> ") || trimmed == ">"

    fun startsScript(trimmed: String) = trimmed.startsWith(">") && trimmed.substring(1).trimStart().startsWith("{%")

    fun endsScript(line: String) = line.contains("%}")
}
