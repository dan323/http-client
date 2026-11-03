package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.ResponseExtractor
import com.dan323.httpclient.core.domain.Header
import com.dan323.httpclient.core.domain.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResponseExtractorTest {
    private val extractor = ResponseExtractor { _, path -> if (path == "$.a") "json-a" else null }

    private fun response(body: String) =
        HttpResponse(201, listOf(Header("X-Id", "42")), body.toByteArray(), 1)

    private val xml = """<root><item id="1">one</item><item id="2">two</item></root>"""

    @Test fun statusAndHeaders() {
        val r = response("")
        assertEquals("201", extractor.extract(r, "status"))
        assertEquals("42", extractor.extract(r, "headers.x-id"))
        assertNull(extractor.extract(r, "headers.Missing"))
    }

    @Test fun wholeBodyAndJson() {
        val r = response("""{"a":1}""")
        assertEquals("""{"a":1}""", extractor.extract(r, "body.*"))
        assertEquals("json-a", extractor.extract(r, "body.$.a"))
    }

    @Test fun xpath() {
        val r = response(xml)
        assertEquals("one", extractor.extract(r, "body.//item"))
        assertEquals("two", extractor.extract(r, "body./root/item[@id='2']"))
        assertEquals("2", extractor.extract(r, "body.xpath:/root/item[2]/@id"))
        assertEquals("2", extractor.extract(r, "body.xpath:count(//item)"))
        assertNull(extractor.extract(r, "body.//nothing"))
        assertNull(extractor.extract(response("not xml"), "body.//a"))
    }

    @Test fun xpathRejectsDoctype() {
        assertNull(extractor.xpath("""<!DOCTYPE a [<!ENTITY x "y">]><a>&x;</a>""", "/a"))
    }

    @Test fun regex() {
        val r = response("token=abc123; other")
        assertEquals("abc123", extractor.extract(r, "body.regex:token=(\\w+)"))
        assertEquals("token=abc123", extractor.extract(r, "body.regex:token=\\w+"))
        assertNull(extractor.extract(r, "body.regex:zzz"))
        assertNull(extractor.extract(r, "body.regex:(unclosed"))
    }

    @Test fun unknown() {
        assertNull(extractor.extract(response(""), "foo"))
    }
}
