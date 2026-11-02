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
}
