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
}
