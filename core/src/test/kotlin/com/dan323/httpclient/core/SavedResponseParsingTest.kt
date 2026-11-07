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

    @Test
    fun `a last body line starting with a save is a directive, with a warning when the path looks like prose`() {
        val f = parser.parse("POST http://x\n\nhello\n>> quoted reply\n\n")
        val r = f.requests.single()
        assertEquals(Body.Text("hello"), r.body)
        assertEquals(ResponseSave("quoted reply", false), r.saveResponse)
        assertEquals(1, f.warnings.size)
        assertTrue("Line 4" in f.warnings[0] && "quoted reply" in f.warnings[0] && "blank line" in f.warnings[0], f.warnings[0])
        // a blank line before it removes the doubt: no warning
        assertTrue(parser.parse("POST http://x\n\nhello\n\n>> quoted reply\n").warnings.isEmpty())
        // links behave alike
        assertEquals(1, parser.parse("POST http://x\n\nhello\n<> see above\n").warnings.size)
    }

    @Test
    fun `a plain path right after a JSON body is a directive without a warning`() {
        val f = parser.parse("POST http://x\nContent-Type: application/json\n\n{\"a\": 1}\n>> out.json\n")
        val r = f.requests.single()
        assertEquals(Body.Text("{\"a\": 1}"), r.body)
        assertEquals(ResponseSave("out.json", false), r.saveResponse)
        assertTrue(f.warnings.isEmpty())
    }

    @Test
    fun `a save line inside a multi-line body followed by more body stays body`() {
        val r = parser.parse("POST http://x\n\nline one\n>> out.json\n<> other.json\nline four\n").requests.single()
        assertNull(r.saveResponse)
        assertTrue(r.responseLinks.isEmpty())
        assertEquals(Body.Text("line one\n>> out.json\n<> other.json\nline four"), r.body)
        // even when only a blank line separates it from the following text
        val r2 = parser.parse("POST http://x\n\none\n>> out.json\n\ntwo\n").requests.single()
        assertNull(r2.saveResponse)
        assertEquals(Body.Text("one\n>> out.json\n\ntwo"), r2.body)
    }

    @Test
    fun `CRLF files parse the same for body plus directive`() {
        val f = parser.parse("POST http://x\r\n\r\nhello\r\n>> out.json\r\n<> l.json\r\n")
        val r = f.requests.single()
        assertEquals(Body.Text("hello"), r.body)
        assertEquals(ResponseSave("out.json", false), r.saveResponse)
        assertEquals(listOf("l.json"), r.responseLinks)
        val prose = parser.parse("POST http://x\r\n\r\nhello\r\n>> quoted reply\r\n").requests.single()
        assertEquals(ResponseSave("quoted reply", false), prose.saveResponse)
        assertEquals(Body.Text("hello"), prose.body)
    }
}
