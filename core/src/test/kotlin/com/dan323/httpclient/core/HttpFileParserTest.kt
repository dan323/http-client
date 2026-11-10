package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.HttpParseException
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.UndefinedVariableException
import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpFileParserTest {
    private val parser = HttpFileParser()

    @Test
    fun `parses multiple requests with names, headers and body`() {
        val file = parser.parse(
            """
            # @name create
            POST {{host}}/users HTTP/1.1
            Content-Type: application/json

            {"name": "a"}

            ### second
            // a comment
            GET {{host}}/users
            Accept: */*
            """.trimIndent()
        )
        assertEquals(2, file.requests.size)
        val (a, b) = file.requests
        assertEquals("create", a.name)
        assertEquals("POST", a.method)
        assertEquals("{{host}}/users", a.url)
        assertEquals(listOf(Header("Content-Type", "application/json")), a.headers)
        assertEquals(Body.Text("""{"name": "a"}"""), a.body)
        assertEquals("GET", b.method)
        assertEquals(null, b.body)
    }

    @Test
    fun `bare url defaults to GET and file body is recognised`() {
        val file = parser.parse("https://example.com/x\n\n###\nPUT https://e.com\n\n< ./payload.json\n")
        assertEquals("GET", file.requests[0].method)
        assertEquals(Body.FromFile("./payload.json"), file.requests[1].body)
    }

    @Test
    fun `runner substitutes variables and rejects undefined ones`() {
        var sent: com.dan323.httpclient.core.domain.HttpRequest? = null
        val runner = RequestRunner(
            { sent = it; HttpResponse(200, emptyList(), ByteArray(0), 1) },
            { ByteArray(0) },
        )
        val t = parser.parse("GET {{host}}/a\nX: {{tok}}").requests.single()
        runner.run(t, mapOf("host" to "http://h", "tok" to "t"))
        assertEquals("http://h/a", sent!!.url)
        assertEquals("t", sent!!.headers.single().value)
        assertFailsWith<UndefinedVariableException> { runner.run(t, mapOf("host" to "h")) }
    }

    @Test
    fun `records the start line of each request block`() {
        val file = parser.parse("GET http://a\n\n###\n\n# @name b\nGET http://b\n")
        assertEquals(listOf(0, 3), file.requests.map { it.startLine })
    }

    @Test
    fun `parses file variables and scopes them from their line onward`() {
        val file = parser.parse(
            "@host = http://a\n@id=1\n\nGET {{host}}/{{id}}\n\n###\n@id = 2\nGET {{host}}/{{id}}\n"
        )
        assertEquals(2, file.requests.size)
        assertEquals(mapOf("host" to "http://a", "id" to "1"), file.requests[0].fileVariables)
        assertEquals(mapOf("host" to "http://a", "id" to "2"), file.requests[1].fileVariables)
        assertEquals(mapOf("host" to "http://a", "id" to "2"), file.variables)
    }

    @Test
    fun `a block with only variables yields no request`() {
        val file = parser.parse("@a = 1\n###\nGET http://x\n")
        assertEquals(1, file.requests.size)
        assertEquals("1", file.requests[0].fileVariables["a"])
    }

    @Test
    fun `parses setting directives and keeps name separate`() {
        val file = parser.parse("# @name n\n# @timeout 5\n// @no-redirect\nGET http://x\n")
        val r = file.requests.single()
        assertEquals("n", r.name)
        assertEquals(mapOf("timeout" to "5", "no-redirect" to ""), r.settings)
    }

    @Test
    fun `at-sign text in bodies and headers is not metadata`() {
        val r = parser.parse("POST http://x\nX: 1\n\n@a = 1\n# @timeout 3\n").requests.single()
        assertEquals(emptyMap(), r.fileVariables)
        assertEquals(emptyMap(), r.settings)
    }

    @Test
    fun `runner resolves file variables over the environment`() {
        val r = parser.parse("@host = http://file\nGET {{host}}/a\n").requests.single()
        val runner = RequestRunner(
            object : com.dan323.httpclient.core.port.out.HttpExecutor { override fun execute(request: com.dan323.httpclient.core.domain.HttpRequest) = error("unused") },
            object : com.dan323.httpclient.core.port.out.FileReader { override fun read(path: String) = ByteArray(0) },
        )
        assertEquals("http://file/a", runner.resolve(r, mapOf("host" to "http://env")).url)
    }

    @Test
    fun `a method line with only an HTTP version has no URL`() {
        val e = kotlin.test.assertFailsWith<HttpParseException> { parser.parse("GET http://x/\n\n###\nQUERY HTTP/1.1\n") }
        assertEquals(4, e.line)
    }

    @Test
    fun `QUERY is a method and keeps its body`() {
        val r = parser.parse("QUERY http://x/search\nContent-Type: text/plain\n\nq=1\n").requests.single()
        assertEquals("QUERY", r.method)
        assertEquals("http://x/search", r.url)
        assertEquals(Body.Text("q=1"), r.body)
    }

    @Test
    fun `unknown method is a parse error with the line number`() {
        val e = assertFailsWith<HttpParseException> { parser.parse("GET http://a\n###\n# c\nGTE http://x\n") }
        assertEquals(4, e.line)
        assertEquals(true, e.message!!.startsWith("Line 4:") && "GTE" in e.message!!)
    }

    @Test
    fun `bare URLs stay GET including with an http version or a variable`() {
        assertEquals("http://x/a", parser.parse("http://x/a").requests.single().url)
        assertEquals("GET", parser.parse("{{host}}/a").requests.single().method)
        val r = parser.parse("localhost:8080/a HTTP/1.1").requests.single()
        assertEquals("GET" to "localhost:8080/a", r.method to r.url)
        val lower = parser.parse("post http://x").requests.single()
        assertEquals("POST", lower.method)
    }

    @Test
    fun `header line without a colon is an error with the line number`() {
        val e = assertFailsWith<HttpParseException> { parser.parse("GET http://x\nAccept: a\nBroken header\n") }
        assertEquals(3, e.line)
        assertFailsWith<HttpParseException> { parser.parse("GET http://x\n: novalue\n") }
    }

    @Test
    fun `comment lines among headers are fine`() {
        val r = parser.parse("GET http://x\n# note\nA: b\n// other\n").requests.single()
        assertEquals(listOf(Header("A", "b")), r.headers)
    }

    @Test
    fun `response handlers are not sent as body and produce warnings, links are kept`() {
        val text = "POST http://x\n\n{\"a\":1}\n\n> {%\n  client.global.set(\"t\", 1);\n%}\n<> out.json\n> handler.js\n"
        val file = parser.parse(text)
        assertEquals(Body.Text("{\"a\":1}"), file.requests.single().body)
        assertEquals(listOf("out.json"), file.requests.single().responseLinks)
        assertEquals(2, file.warnings.size)
        assertEquals(true, file.warnings[0].startsWith("Line 5:"))
        assertEquals(true, file.warnings[1].startsWith("Line 9:"))
    }

    @Test
    fun `single-line script and handler-only request have no body`() {
        val file = parser.parse("GET http://x\n\n> {% client.test(\"a\", f); %}\n")
        assertEquals(null, file.requests.single().body)
        assertEquals(1, file.warnings.size)
    }

    @Test
    fun `handler right after headers without a blank line`() {
        val file = parser.parse("GET http://x\nA: b\n> {% x %}\n")
        assertEquals(listOf(Header("A", "b")), file.requests.single().headers)
        assertEquals(null, file.requests.single().body)
        assertEquals(1, file.warnings.size)
    }

    @Test
    fun `html body is text and a file body needs a space`() {
        assertEquals(Body.Text("<html>"), parser.parse("POST http://x\n\n<html>").requests.single().body)
        assertEquals(Body.FromFile("a.json"), parser.parse("POST http://x\n\n< a.json").requests.single().body)
    }

    @Test
    fun `clean files have no warnings`() {
        assertEquals(emptyList(), parser.parse("GET http://x\n").warnings)
    }

    @Test
    fun `query continuation lines extend the URL`() {
        val r = parser.parse("GET http://x/a\n    ?a=1\n    &b=2\nAccept: */*\n").requests.single()
        assertEquals("http://x/a?a=1&b=2", r.url)
        assertEquals(1, r.headers.size)
    }

    @Test
    fun `folded header values are joined`() {
        val r = parser.parse("GET http://x\nX: a,\n   b\n").requests.single()
        assertEquals("a, b", r.headers.single().value)
    }

    @Test
    fun `GRAPHQL WEBSOCKET and GRPC are parse errors naming the line, not sent as HTTP`() {
        for (word in listOf("GRAPHQL", "WEBSOCKET", "GRPC", "grpc")) {
            val e = assertFailsWith<HttpParseException> { parser.parse("GET http://a\n###\n# c\n$word http://x/g\n") }
            assertEquals(4, e.line)
            assertEquals("Line 4: ${word.uppercase()} requests are not supported (only HTTP methods are sent)", e.message)
        }
        val lone = assertFailsWith<HttpParseException> { parser.parse("WEBSOCKET\n") }
        assertEquals(true, "WEBSOCKET requests are not supported" in lone.message!!)
    }

    @Test
    fun `protocol words inside a URL or a body are not rejected`() {
        val file = parser.parse("GET http://h/grpc\n###\nGET http://h/graphql\n###\nPOST http://h/x\n\ngraphql {{x}}\nwebsocket here\n")
        assertEquals(3, file.requests.size)
        assertEquals("http://h/grpc", file.requests[0].url)
        assertEquals("http://h/graphql", file.requests[1].url)
        assertEquals("graphql {{x}}\nwebsocket here", (file.requests[2].body as Body.Text).content)
        // a bare URL (no method) and a scheme that merely starts with the word are still plain GETs
        val bare = parser.parse("grpc://host/x\n###\nhttp://h/websocket\n").requests
        assertEquals(listOf("GET", "GET"), bare.map { it.method })
    }

    @Test
    fun `a typo of a protocol word is an unknown method and gets no did-you-mean`() {
        val e = assertFailsWith<HttpParseException> { parser.parse("GRAPHQ http://x\n") }
        assertEquals(true, e.message!!.startsWith("Line 1:") && "GRAPHQ" in e.message!!)
        assertEquals(false, "did you mean" in e.message!!)
    }

    @Test
    fun `a near-miss method suggests the real one`() {
        val e = assertFailsWith<HttpParseException> { parser.parse("GTE http://x\n") }
        assertEquals(true, "did you mean 'GET'" in e.message!!)
    }

    @Test
    fun `a bare method without URL is a parse error`() {
        val e = assertFailsWith<HttpParseException> { parser.parse("GET\n") }
        assertEquals(1, e.line)
    }

    @Test
    fun `quoted lines in the body are kept but trailing handlers are not`() {
        val r = parser.parse("POST http://x\n\n> quoted\nreal\n\n> ./h.js\n").requests.single()
        assertEquals("> quoted\nreal", (r.body as com.dan323.httpclient.core.domain.Body.Text).content)
    }

    @Test
    fun `a handler directly after headers is not a header`() {
        val f = parser.parse("GET http://x\nA: b\n> {% x:y %}\n")
        assertEquals(1, f.requests.single().headers.size)
        assertEquals(1, f.warnings.size)
    }

    @Test
    fun `percent brace inside a script string does not end the script`() {
        val f = parser.parse("POST http://x\n\nbody\n> {%\nvar s = '%}';\n%}\n")
        assertEquals("body", (f.requests.single().body as com.dan323.httpclient.core.domain.Body.Text).content)
    }

    @Test
    fun `an unterminated script warns`() {
        val f = parser.parse("GET http://x\n\n> {%\nfoo\n")
        assertEquals(true, f.warnings.any { "unterminated" in it })
    }

    @Test
    fun `substituting file reference is a file body with a warning`() {
        val f = parser.parse("POST http://x\n\n<@ ./f.json\n")
        assertEquals(com.dan323.httpclient.core.domain.Body.FromFile("./f.json"), f.requests.single().body)
        assertEquals(true, f.warnings.any { "<@" in it })
    }
}
