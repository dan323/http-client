package com.dan323.httpclient.core.application

enum class HttpTokenKind { WHITESPACE, SEPARATOR, COMMENT, METHOD, URL, HEADER_NAME, COLON, HEADER_VALUE, BODY_TEXT, FILE_REF, VARIABLE }

/** A token covering `[start, end)`; offsets are relative to the text that was lexed. */
data class HttpToken(val kind: HttpTokenKind, val start: Int, val end: Int)

/**
 * Line-based lexer for `.http` text, shared by the editor front ends. Where a line sits in a request is
 * the [mode]: [BEFORE] a request line, in the [HEADERS], or in the [BODY]. `###` resets to [BEFORE].
 */
object HttpLineLexer {
    const val BEFORE = 0
    const val HEADERS = 1
    const val BODY = 2

    private val methods = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT")

    class LineResult(val tokens: List<HttpToken>, val nextMode: Int)

    /** Lexes one line (without its line break); token offsets are relative to the line start. */
    fun lexLine(line: String, mode: Int): LineResult {
        val out = mutableListOf<HttpToken>()
        fun add(kind: HttpTokenKind, from: Int, to: Int) { if (from < to) out += HttpToken(kind, from, to) }
        // Splits {{variables}} out of a text segment.
        fun addText(kind: HttpTokenKind, from: Int, to: Int) {
            var i = from
            while (i < to) {
                val open = line.indexOf("{{", i)
                val close = if (open in 0 until to) line.indexOf("}}", open + 2) else -1
                if (open !in 0 until to || close < 0 || close + 2 > to) { add(kind, i, to); return }
                add(kind, i, open)
                add(HttpTokenKind.VARIABLE, open, close + 2)
                i = close + 2
            }
        }

        val trimmed = line.trim()
        var next = mode
        when {
            line.startsWith("###") -> { add(HttpTokenKind.SEPARATOR, 0, line.length); next = BEFORE }
            trimmed.isEmpty() -> { add(HttpTokenKind.WHITESPACE, 0, line.length); if (mode == HEADERS) next = BODY }
            mode == BODY ->
                if (trimmed.startsWith("<")) add(HttpTokenKind.FILE_REF, 0, line.length)
                else addText(HttpTokenKind.BODY_TEXT, 0, line.length)
            trimmed.startsWith("#") || trimmed.startsWith("//") -> add(HttpTokenKind.COMMENT, 0, line.length)
            mode == BEFORE -> {
                val indent = line.length - line.trimStart().length
                add(HttpTokenKind.WHITESPACE, 0, indent)
                val wordEnd = line.indexOf(' ', indent).let { if (it < 0) line.length else it }
                var urlStart = indent
                if (line.substring(indent, wordEnd).uppercase() in methods && wordEnd < line.length) {
                    add(HttpTokenKind.METHOD, indent, wordEnd)
                    val gap = line.length - line.substring(wordEnd).trimStart().length
                    add(HttpTokenKind.WHITESPACE, wordEnd, gap)
                    urlStart = gap
                }
                addText(HttpTokenKind.URL, urlStart, line.length)
                next = HEADERS
            }
            else -> { // HEADERS
                val colon = line.indexOf(':')
                if (colon > 0) {
                    add(HttpTokenKind.HEADER_NAME, 0, colon)
                    add(HttpTokenKind.COLON, colon, colon + 1)
                    addText(HttpTokenKind.HEADER_VALUE, colon + 1, line.length)
                } else addText(HttpTokenKind.HEADER_VALUE, 0, line.length)
            }
        }
        return LineResult(out, next)
    }

    /** Lexes a whole document. Line breaks are not covered by any token. */
    fun tokenize(text: String): List<HttpToken> {
        val out = mutableListOf<HttpToken>()
        var mode = BEFORE
        var offset = 0
        for (line in text.split('\n')) {
            val r = lexLine(line, mode)
            r.tokens.mapTo(out) { HttpToken(it.kind, it.start + offset, it.end + offset) }
            mode = r.nextMode
            offset += line.length + 1
        }
        return out
    }

    /** Zero-based line numbers of the request lines (method + URL, or a bare URL meaning GET). */
    fun requestLines(text: String): Set<Int> {
        val out = mutableSetOf<Int>()
        var mode = BEFORE
        text.split('\n').forEachIndexed { index, line ->
            val r = lexLine(line, mode)
            if (mode == BEFORE && r.nextMode == HEADERS) out += index
            mode = r.nextMode
        }
        return out
    }
}
