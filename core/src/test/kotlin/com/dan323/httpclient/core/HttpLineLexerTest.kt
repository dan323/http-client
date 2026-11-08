package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.HttpLineLexer
import com.dan323.httpclient.core.application.HttpTokenKind
import com.dan323.httpclient.core.application.HttpTokenKind.*
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpLineLexerTest {
    private fun kinds(text: String) =
        HttpLineLexer.tokenize(text).filter { it.kind != WHITESPACE }.map { it.kind to text.substring(it.start, it.end) }

    @Test fun classifiesRequestParts() {
        assertEquals(
            listOf(
                METHOD to "POST", VARIABLE to "{{base}}", URL to "/x",
                HEADER_NAME to "Accept", COLON to ":", HEADER_VALUE to " */*",
                BODY_TEXT to "body", SEPARATOR to "###", METHOD to "GET", URL to "/y",
            ),
            kinds("POST {{base}}/x\nAccept: */*\n\nbody\n###\nGET /y\n"),
        )
    }

    @Test fun `QUERY is highlighted as a method`() {
        assertEquals(listOf(METHOD to "QUERY", URL to "/s"), kinds("QUERY /s\n").take(2))
    }

    @Test fun `a rejected protocol word gets no method token, like an unknown method`() {
        val text = "GRPC localhost:50051/x\nGTE http://h/y\n"
        val methods = HttpLineLexer.tokenize(text).filter { it.kind == METHOD }
        assertEquals(emptyList(), methods.map { text.substring(it.start, it.end) })
        assertEquals(listOf(METHOD to "GET"), kinds("GET http://h/grpc\n").take(1))
    }

    @Test fun commentsBareUrlsAndFileBodies() {
        assertEquals(
            listOf(COMMENT to "// c", URL to "http://h/z", FILE_REF to "< ./f.json"),
            kinds("// c\nhttp://h/z\n\n< ./f.json\n"),
        )
    }

    @Test fun variablesInHeadersAndBodies() {
        assertEquals(
            listOf(METHOD to "GET", URL to "/u", HEADER_NAME to "A", COLON to ":", HEADER_VALUE to " Bearer ", VARIABLE to "{{login.response.body.$.t}}",
                BODY_TEXT to "{\"k\": \"", VARIABLE to "{{b}}", BODY_TEXT to "\"}"),
            kinds("GET /u\nA: Bearer {{login.response.body.$.t}}\n\n{\"k\": \"{{b}}\"}"),
        )
    }

    @Test fun unclosedBracesAreOrdinaryText() {
        assertEquals(listOf<Pair<HttpTokenKind, String>>(METHOD to "GET", URL to "/a/{{b"), kinds("GET /a/{{b"))
    }

    @Test fun tokensAreOrderedAndNeverOverlap() {
        val text = "# @name a\nPOST {{base}}/x HTTP/1.1\nA: {{v}}\n\n{\"k\": \"{{b}}\"}\n###\nhttp://h\n"
        val tokens = HttpLineLexer.tokenize(text)
        tokens.zipWithNext().forEach { (a, b) -> assert(a.end <= b.start) { "$a overlaps $b" } }
        assertEquals(text.length - text.count { it == '\n' }, tokens.sumOf { it.end - it.start })
    }

    @Test
    fun `request lines include bare URLs that start with a variable`() {
        val text = "# @name a\n{{host}}/get\nAccept: x\n\n{{body}}\n###\nPOST {{host}}/p\n\n{{x}}\n"
        assertEquals(setOf(1, 6), HttpLineLexer.requestLines(text))
    }

    @Test
    fun `file variables before the request line are not the URL and the GET line is a request`() {
        val text = "@host = http://h\n@token=abc\nGET {{host}}/x\nA: b\n"
        assertEquals(setOf(2), HttpLineLexer.requestLines(text))
        assertEquals(
            listOf(HEADER_NAME to "@host", COLON to "=", HEADER_VALUE to " http://h",
                HEADER_NAME to "@token", COLON to "=", HEADER_VALUE to "abc",
                METHOD to "GET", VARIABLE to "{{host}}", URL to "/x", HEADER_NAME to "A", COLON to ":", HEADER_VALUE to " b"),
            kinds(text),
        )
    }

    @Test
    fun `file variable between comments and request line`() {
        assertEquals(setOf(2), HttpLineLexer.requestLines("# @name a\n@x = 1\nhttp://h/z\n"))
    }

    @Test
    fun `only a less-than followed by a space is a file reference`() {
        assertEquals(
            listOf(METHOD to "POST", URL to "/x", BODY_TEXT to "<html>", BODY_TEXT to "<b>hi</b>"),
            kinds("POST /x\n\n<html>\n<b>hi</b>"),
        )
        assertEquals(listOf(METHOD to "POST", URL to "/x", FILE_REF to "< ./a.json"), kinds("POST /x\n\n< ./a.json"))
    }

    @Test
    fun `response handlers are comments, including multi-line scripts`() {
        val text = "GET /x\n\n> {%\n  client.log(\"hi\");\n%}\n<> out.json\n> handler.js\nbody"
        assertEquals(
            listOf(METHOD to "GET", URL to "/x", COMMENT to "> {%", COMMENT to "  client.log(\"hi\");", COMMENT to "%}",
                COMMENT to "<>", FILE_REF to "out.json", COMMENT to "> handler.js", BODY_TEXT to "body"),
            kinds(text),
        )
        assertEquals(listOf(COMMENT to "> {% single %}", BODY_TEXT to "after"), kinds("GET /x\n\n> {% single %}\nafter").drop(2))
    }

    @Test
    fun `lexer modes are an enum and a script line keeps its mode`() {
        assertEquals(HttpLineLexer.Mode.SCRIPT, HttpLineLexer.lexLine("> {%", HttpLineLexer.Mode.BODY).nextMode)
        assertEquals(HttpLineLexer.Mode.BODY, HttpLineLexer.lexLine("%}", HttpLineLexer.Mode.SCRIPT).nextMode)
        assertEquals(HttpLineLexer.Mode.SCRIPT, HttpLineLexer.lexLine("", HttpLineLexer.Mode.SCRIPT).nextMode)
    }

    @Test
    fun `lexer and parser agree on request lines`() {
        val text = "@a = 1\n# @name n\nPOST /p\nX: y\n\n<html>\n###\n@b = 2\nhttp://h/z\n\n###\nGET /last\n"
        val parsed = com.dan323.httpclient.core.application.HttpFileParser().parse(text).requests
        assertEquals(parsed.map { it.method }, listOf("POST", "GET", "GET"))
        assertEquals(setOf(2, 8, 11), HttpLineLexer.requestLines(text))
    }

    @Test
    fun `handler right after headers enters script mode`() {
        val tokens = HttpLineLexer.tokenize("GET http://x\nA: b\n> {%\nx: y\n%}\n")
        assertEquals(HttpTokenKind.COMMENT, tokens.first { it.start >= 18 }.kind)
        assertEquals(false, tokens.any { it.start >= 22 && it.kind == HttpTokenKind.HEADER_NAME })
        assertEquals(HttpLineLexer.Mode.BODY, HttpLineLexer.lexLine("> {% a %}", HttpLineLexer.Mode.HEADERS).nextMode)
    }

    @Test
    fun `query continuation is URL and CRLF is excluded from tokens`() {
        val text = "GET http://x\r\n  ?a=1\r\nA: b\r\n"
        val t = HttpLineLexer.tokenize(text)
        assertEquals(true, t.none { text.substring(it.start, it.end).contains('\r') })
        assertEquals("?a=1", text.substring(t.first { it.kind == HttpTokenKind.URL && it.start > 12 }.start, t.first { it.kind == HttpTokenKind.URL && it.start > 12 }.end))
    }
}
