package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.HttpFileParser
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
}
