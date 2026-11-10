package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.EncodedPart
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.HttpParseException
import com.dan323.httpclient.core.application.MultipartEncoder
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.withDependencies
import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpRequest
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.PartContent
import com.dan323.httpclient.core.port.out.HttpExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultipartTest {
    private val parser = HttpFileParser()

    private fun request(body: String, contentType: String = "multipart/form-data; boundary=B") =
        "POST http://x/up\nContent-Type: $contentType\n\n$body"

    private val twoParts = """
        --B
        Content-Disposition: form-data; name="title"

        hello {{user}}
        --B
        Content-Disposition: form-data; name="data"; filename="a.json"
        Content-Type: application/json

        < ./a.json
        --B--
    """.trimIndent()

    @Test fun `parses a text part and a file part`() {
        val file = parser.parse(request(twoParts))
        val body = file.requests.single().body as Body.Multipart
        assertEquals("B", body.boundary)
        assertEquals(2, body.parts.size)
        assertEquals(PartContent.Text("hello {{user}}"), body.parts[0].content)
        assertEquals(listOf(Header("Content-Disposition", "form-data; name=\"title\"")), body.parts[0].headers)
        assertEquals(PartContent.File("./a.json"), body.parts[1].content)
        assertEquals(2, body.parts[1].headers.size)
        assertEquals(emptyList(), file.warnings)
    }

    @Test fun `a quoted boundary and a different case of the type are accepted`() {
        val body = parser.parse(request(twoParts, "Multipart/Mixed; charset=x; BOUNDARY=\"B\"")).requests.single().body
        assertTrue(body is Body.Multipart && body.boundary == "B")
    }

    @Test fun `multi-line text content keeps its line breaks`() {
        val text = "--B\nContent-Disposition: form-data; name=\"t\"\n\nl1\nl2\n--B--"
        val body = parser.parse(request(text)).requests.single().body as Body.Multipart
        assertEquals(PartContent.Text("l1\nl2"), body.parts.single().content)
    }

    @Test fun `a line that only starts like the boundary is content`() {
        val text = "--B\nContent-Disposition: form-data; name=\"t\"\n\n--Bx not a delimiter\n--B--"
        val body = parser.parse(request(text)).requests.single().body as Body.Multipart
        assertEquals(PartContent.Text("--Bx not a delimiter"), body.parts.single().content)
    }

    @Test fun `multipart without a boundary warns and stays text`() {
        val file = parser.parse(request("plain", "multipart/form-data"))
        assertEquals(Body.Text("plain"), file.requests.single().body)
        assertTrue(file.warnings.single().contains("without a boundary"))
    }

    @Test fun `a variable in the boundary is an error`() {
        val e = assertFailsWith<HttpParseException> { parser.parse(request(twoParts, "multipart/form-data; boundary={{b}}")) }
        assertEquals(1, e.line)
    }

    @Test fun `a missing closing delimiter is an error at the last body line`() {
        val e = assertFailsWith<HttpParseException> {
            parser.parse(request("--B\nContent-Disposition: form-data; name=\"t\"\n\nx"))
        }
        assertEquals(7, e.line)
        assertTrue(e.message!!.contains("closing"))
    }

    @Test fun `text before the first delimiter or after the closing one is an error`() {
        assertFailsWith<HttpParseException> { parser.parse(request("oops\n--B--")) }
        assertFailsWith<HttpParseException> { parser.parse(request("--B\nContent-Disposition: form-data; name=\"t\"\n\nx\n--B--\ntrailing")) }
    }

    @Test fun `a part needs a Content-Disposition header`() {
        val e = assertFailsWith<HttpParseException> { parser.parse(request("--B\nContent-Type: text/plain\n\nx\n--B--")) }
        assertEquals(4, e.line)
        assertTrue(e.message!!.contains("Content-Disposition"))
    }

    @Test fun `a part header without a colon is an error`() {
        assertFailsWith<HttpParseException> { parser.parse(request("--B\nnope\n\nx\n--B--")) }
    }

    @Test fun `a part with no content is an empty text part`() {
        val body = parser.parse(request("--B\nContent-Disposition: form-data; name=\"t\"\n\n--B--")).requests.single().body as Body.Multipart
        assertEquals(PartContent.Text(""), body.parts.single().content)
    }

    @Test fun `an at-sign file line in a part warns and is sent as text`() {
        val file = parser.parse(request("--B\nContent-Disposition: form-data; name=\"f\"\n\n<@ ./a.json\n--B--"))
        assertEquals(PartContent.Text("<@ ./a.json"), (file.requests.single().body as Body.Multipart).parts.single().content)
        assertTrue(file.warnings.single().startsWith("Line 7:"))
    }

    @Test fun `a whole-body file reference with a multipart type stays a file body`() {
        assertEquals(Body.FromFile("./all.bin"), parser.parse(request("< ./all.bin")).requests.single().body)
    }

    @Test fun `other content types keep a plain text body`() {
        val body = parser.parse(request("--B\nx\n--B--", "text/plain")).requests.single().body
        assertTrue(body is Body.Text)
    }

    @Test fun `encoder writes CRLF structure and leaves content untouched`() {
        val bytes = MultipartEncoder.encode(
            "B",
            listOf(
                EncodedPart(listOf(Header("Content-Disposition", "form-data; name=\"a\"")), "l1\nl2".toByteArray()),
                EncodedPart(listOf(Header("Content-Disposition", "form-data; name=\"f\"")), byteArrayOf(0, 10, 13, -1)),
            ),
        )
        val expected = "--B\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nl1\nl2\r\n".toByteArray() +
            "--B\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\n".toByteArray() + byteArrayOf(0, 10, 13, -1) +
            "\r\n--B--\r\n".toByteArray()
        assertTrue(expected.contentEquals(bytes))
    }

    private fun runner(sent: MutableList<HttpRequest> = mutableListOf(), files: (String) -> ByteArray = { error("no files") }) =
        RequestRunner(HttpExecutor { sent += it; HttpResponse(200, emptyList(), ByteArray(0), 1) }, { files(it) })

    @Test fun `the runner resolves variables in text parts and headers and reads file parts`() {
        val template = parser.parse(
            request(twoParts.replace("filename=\"a.json\"", "filename=\"{{name}}\"")),
        ).requests.single()
        val sent = mutableListOf<HttpRequest>()
        val read = mutableListOf<String>()
        runner(sent) { read += it; byteArrayOf(1, 2, 3) }.run(template, mapOf("user" to "dan", "name" to "x.json"))
        assertEquals(listOf("./a.json"), read)
        val text = sent.single().body!!.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("\r\n\r\nhello dan\r\n--B\r\n"), text)
        assertTrue(text.contains("filename=\"x.json\""), text)
        assertTrue(text.endsWith("\u0001\u0002\u0003\r\n--B--\r\n"), text)
    }

    @Test fun `a failing file read fails the request like a file body does`() {
        val template = parser.parse(request(twoParts)).requests.single()
        assertFailsWith<java.io.IOException> { runner { throw java.io.IOException("outside the allowed folders") }.run(template, mapOf("user" to "u")) }
    }

    @Test fun `an undefined variable in a part is reported`() {
        val template = parser.parse(request(twoParts)).requests.single()
        assertFailsWith<com.dan323.httpclient.core.application.UndefinedVariableException> { runner().run(template, emptyMap()) }
    }

    @Test fun `a part that reads a response makes the request depend on the earlier one`() {
        val file = parser.parse("# @name login\nGET http://x/l\n\n###\n" + request("--B\nContent-Disposition: form-data; name=\"t\"\n\n{{login.response.body.${'$'}.id}}\n--B--"))
        val upload = file.requests.last()
        assertEquals(file.requests, withDependencies(file.requests, listOf(upload)))
        assertNull(file.requests.first().body)
    }
}
