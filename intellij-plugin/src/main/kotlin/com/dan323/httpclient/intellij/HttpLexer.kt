package com.dan323.httpclient.intellij

import com.dan323.httpclient.core.application.HttpLineLexer
import com.dan323.httpclient.core.application.HttpTokenKind
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
    private var mode = HttpLineLexer.BEFORE
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
        val result = HttpLineLexer.lexLine(buffer.subSequence(lineStart, contentEnd).toString(), mode)
        result.tokens.forEachIndexed { i, t ->
            pending += Tok(elementType(t.kind), lineStart + t.start, lineStart + t.end, if (i == 0) mode else mode + 3)
        }
        if (lineEnd > contentEnd) pending += Tok(TokenType.WHITE_SPACE, contentEnd, lineEnd, mode + 3)
        mode = result.nextMode
        pos = lineEnd
    }

    private fun elementType(kind: HttpTokenKind): IElementType = when (kind) {
        HttpTokenKind.WHITESPACE -> TokenType.WHITE_SPACE
        HttpTokenKind.SEPARATOR -> HttpTokens.SEPARATOR
        HttpTokenKind.COMMENT -> HttpTokens.COMMENT
        HttpTokenKind.METHOD -> HttpTokens.METHOD
        HttpTokenKind.URL -> HttpTokens.URL
        HttpTokenKind.HEADER_NAME -> HttpTokens.HEADER_NAME
        HttpTokenKind.COLON -> HttpTokens.COLON
        HttpTokenKind.HEADER_VALUE -> HttpTokens.HEADER_VALUE
        HttpTokenKind.BODY_TEXT -> HttpTokens.BODY_TEXT
        HttpTokenKind.FILE_REF -> HttpTokens.FILE_REF
        HttpTokenKind.VARIABLE -> HttpTokens.VARIABLE
    }
}
