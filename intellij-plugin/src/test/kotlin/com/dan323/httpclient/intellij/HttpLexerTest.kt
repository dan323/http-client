package com.dan323.httpclient.intellij

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpLexerTest {
    private fun lex(text: String): List<Pair<IElementType, String>> {
        val lexer = HttpLexer()
        lexer.start(text, 0, text.length, 0)
        val out = mutableListOf<Pair<IElementType, String>>()
        while (lexer.tokenType != null) {
            out += lexer.tokenType!! to text.substring(lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return out
    }

    @Test fun tokensCoverTheWholeText() {
        val text = "# @name a\nPOST {{base}}/x HTTP/1.1\nA: {{v}}\n\n{\"k\": \"{{b}}\"}\n###\nhttp://h\n"
        assertEquals(text, lex(text).joinToString("") { it.second })
    }

    @Test fun classifiesRequestParts() {
        val tokens = lex("POST {{base}}/x\nAccept: */*\n\nbody\n###\nGET /y\n").filter { it.first != TokenType.WHITE_SPACE }
        assertEquals(
            listOf(
                HttpTokens.METHOD to "POST", HttpTokens.VARIABLE to "{{base}}", HttpTokens.URL to "/x",
                HttpTokens.HEADER_NAME to "Accept", HttpTokens.COLON to ":", HttpTokens.HEADER_VALUE to " */*",
                HttpTokens.BODY_TEXT to "body", HttpTokens.SEPARATOR to "###",
                HttpTokens.METHOD to "GET", HttpTokens.URL to "/y",
            ),
            tokens,
        )
    }

    @Test fun bareUrlAndCommentsAndFileBody() {
        val tokens = lex("// c\nhttp://h/z\n\n< ./f.json\n").filter { it.first != TokenType.WHITE_SPACE }
        assertEquals(
            listOf(HttpTokens.COMMENT to "// c", HttpTokens.URL to "http://h/z", HttpTokens.FILE_REF to "< ./f.json"),
            tokens,
        )
    }

    @Test fun restartAtStateZeroLineStartMatchesFullLex() {
        val text = "GET /a\n\nbody\n###\nPOST /b\nX: y\n"
        val lexer = HttpLexer()
        lexer.start(text, 0, text.length, 0)
        var restart = -1
        while (lexer.tokenType != null) {
            if (lexer.state == 0 && lexer.tokenStart > 0) restart = lexer.tokenStart
            lexer.advance()
        }
        val tail = HttpLexer().also { it.start(text, restart, text.length, 0) }
        val full = lex(text).filter { it.second.isNotEmpty() }.takeLastWhile { true }
        val tailTokens = mutableListOf<String>()
        while (tail.tokenType != null) { tailTokens += text.substring(tail.tokenStart, tail.tokenEnd); tail.advance() }
        assertEquals(full.map { it.second }.takeLast(tailTokens.size), tailTokens)
    }

    @Test fun restartingAtAStateZeroLineMatchesAFullLex() {
        val text = "GET http://a\nA: b\n\nbody\n###\nPOST http://c\nX: y\n"
        val full = lex(text)
        val restart = text.indexOf("POST")
        val lexer = HttpLexer()
        lexer.start(text, restart, text.length, 0)
        val tail = mutableListOf<Pair<IElementType, String>>()
        while (lexer.tokenType != null) { tail += lexer.tokenType!! to text.substring(lexer.tokenStart, lexer.tokenEnd); lexer.advance() }
        assertEquals(full.takeLast(tail.size), tail)
    }

    @Test fun crlfTokensCoverTheTextWithoutCarriageReturnsInValues() {
        val text = "GET http://a\r\nA: b\r\n"
        val tokens = lex(text)
        assertEquals(text, tokens.joinToString("") { it.second })
        assertEquals(true, tokens.none { it.first != TokenType.WHITE_SPACE && it.second.contains('\r') })
    }
}
