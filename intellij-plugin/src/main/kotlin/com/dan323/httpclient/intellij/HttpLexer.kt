package com.dan323.httpclient.intellij

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * Line-based lexer for `.http` files. Mode (where we are in a request) is the lexer state at the start
 * of a line; state 0 is a line start outside a request, so the IDE can restart lexing there.
 */
class HttpLexer : LexerBase() {
    private class Tok(val type: IElementType, val start: Int, val end: Int, val state: Int)

    private var buffer: CharSequence = ""
    private var endOffset = 0
    private var pos = 0
    private var mode = BEFORE
    private val pending = ArrayDeque<Tok>()
    private var current: Tok? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        pos = startOffset
        mode = initialState % 3
        pending.clear()
        advance()
    }

    override fun getState() = current?.state ?: 0
    override fun getTokenType() = current?.type
    override fun getTokenStart() = current?.start ?: endOffset
    override fun getTokenEnd() = current?.end ?: endOffset
    override fun getBufferSequence() = buffer
    override fun getBufferEnd() = endOffset

    override fun advance() {
        if (pending.isEmpty() && pos < endOffset) lexLine()
        current = pending.removeFirstOrNull()
    }

    private fun lexLine() {
        val lineStart = pos
        var contentEnd = lineStart
        while (contentEnd < endOffset && buffer[contentEnd] != '\n') contentEnd++
        val lineEnd = if (contentEnd < endOffset) contentEnd + 1 else contentEnd
        val stateAtLineStart = mode
        var first = true
        fun add(type: IElementType, from: Int, to: Int) {
            if (from >= to) return
            pending += Tok(type, from, to, if (first) stateAtLineStart else stateAtLineStart + 3)
            first = false
        }
        // Splits {{variables}} out of a text segment.
        fun addText(type: IElementType, from: Int, to: Int) {
            var i = from
            while (i < to) {
                val open = indexOf("{{", i, to)
                val close = if (open >= 0) indexOf("}}", open + 2, to) else -1
                if (open < 0 || close < 0) { add(type, i, to); return }
                add(type, i, open)
                add(HttpTokens.VARIABLE, open, close + 2)
                i = close + 2
            }
        }

        val line = buffer.subSequence(lineStart, contentEnd).toString()
        val trimmed = line.trim()
        when {
            line.startsWith("###") -> { add(HttpTokens.SEPARATOR, lineStart, contentEnd); mode = BEFORE }
            trimmed.isEmpty() -> { add(TokenType.WHITE_SPACE, lineStart, contentEnd); if (mode == HEADERS) mode = BODY }
            mode == BODY ->
                if (trimmed.startsWith("<")) add(HttpTokens.FILE_REF, lineStart, contentEnd)
                else addText(HttpTokens.BODY_TEXT, lineStart, contentEnd)
            trimmed.startsWith("#") || trimmed.startsWith("//") -> add(HttpTokens.COMMENT, lineStart, contentEnd)
            mode == BEFORE -> {
                val indent = line.length - line.trimStart().length
                add(TokenType.WHITE_SPACE, lineStart, lineStart + indent)
                val wordEnd = line.indexOf(' ', indent).let { if (it < 0) line.length else it }
                val word = line.substring(indent, wordEnd)
                var urlStart = lineStart + indent
                if (word.uppercase() in METHODS && wordEnd < line.length) {
                    add(HttpTokens.METHOD, urlStart, lineStart + wordEnd)
                    val gap = line.length - line.substring(wordEnd).trimStart().length
                    add(TokenType.WHITE_SPACE, lineStart + wordEnd, lineStart + gap)
                    urlStart = lineStart + gap
                }
                addText(HttpTokens.URL, urlStart, contentEnd)
                mode = HEADERS
            }
            else -> { // HEADERS
                val colon = line.indexOf(':')
                if (colon > 0) {
                    add(HttpTokens.HEADER_NAME, lineStart, lineStart + colon)
                    add(HttpTokens.COLON, lineStart + colon, lineStart + colon + 1)
                    addText(HttpTokens.HEADER_VALUE, lineStart + colon + 1, contentEnd)
                } else addText(HttpTokens.HEADER_VALUE, lineStart, contentEnd)
            }
        }
        add(TokenType.WHITE_SPACE, contentEnd, lineEnd)
        pos = lineEnd
    }

    private fun indexOf(s: String, from: Int, to: Int): Int {
        var i = from
        while (i + s.length <= to) {
            if (s.indices.all { buffer[i + it] == s[it] }) return i
            i++
        }
        return -1
    }

    private companion object {
        const val BEFORE = 0
        const val HEADERS = 1
        const val BODY = 2
        val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT")
    }
}
