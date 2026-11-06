package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.HttpLineLexer
import com.dan323.httpclient.core.application.HttpParseException
import com.dan323.httpclient.core.application.HttpTokenKind.*
import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.ResponseSave
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedResponseParsingTest {
    private val parser = HttpFileParser()

    @Test
    fun `save, overwrite and link after the body`() {
        val f = parser.parse("POST http://x\n\n{\"a\":1}\n\n>> out/a.json\n<> last.json\n")
        val r = f.requests.single()
        assertEquals(Body.Text("{\"a\":1}"), r.body)
        assertEquals(ResponseSave("out/a.json", false), r.saveResponse)
        assertEquals(listOf("last.json"), r.responseLinks)
        assertTrue(f.warnings.isEmpty())
        assertEquals(ResponseSave("a b.json", true), parser.parse("GET http://x\n>>! a b.json\n").requests.single().saveResponse)
    }

    @Test
    fun `after a handler script, with variables and CRLF, no body`() {
        val text = "GET http://x/{{id}}\r\n\r\n> {%\r\n client.log(1);\r\n%}\r\n>> out-{{id}}.json\r\n<> a.json\r\n<> b.json\r\n"
        val f = parser.parse(text)
        val r = f.requests.single()
        assertEquals(null, r.body)
        assertEquals(ResponseSave("out-{{id}}.json", false), r.saveResponse)
        assertEquals(listOf("a.json", "b.json"), r.responseLinks)
        assertEquals(1, f.warnings.size) // only the script
    }

    @Test
    fun `lines starting like a save before body text are body`() {
        val r = parser.parse("POST http://x\n\n>> not a directive\nreal body\n").requests.single()
        assertNull(r.saveResponse)
        assertEquals(Body.Text(">> not a directive\nreal body"), r.body)
        assertNull(parser.parse("POST http://x\n\n>>x\n").requests.single().saveResponse)
    }

    @Test
    fun `empty path and several saves are parse errors with the line`() {
        assertEquals("Line 3: '>>' needs a file path", assertFailsWith<HttpParseException> { parser.parse("GET http://x\n\n>> \n") }.message)
        assertEquals("Line 2: '>>!' needs a file path", assertFailsWith<HttpParseException> { parser.parse("GET http://x\n>>!\n") }.message)
        assertEquals("Line 3: '<>' needs a file path", assertFailsWith<HttpParseException> { parser.parse("GET http://x\n\n<>\n") }.message)
        val e = assertFailsWith<HttpParseException> { parser.parse("GET http://x\n>> a\n>>! b\n") }
        assertEquals(3, e.line)
    }

    @Test
    fun `lexer highlights the marker as comment and the path as file reference`() {
        val kinds = HttpLineLexer.tokenize("GET /x\n\n>>! o-{{v}}.json").map { it.kind }.filter { it != WHITESPACE }
        assertEquals(listOf(METHOD, URL, COMMENT, FILE_REF, VARIABLE, FILE_REF), kinds)
    }
}
