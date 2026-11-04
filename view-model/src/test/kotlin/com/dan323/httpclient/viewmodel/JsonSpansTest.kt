package com.dan323.httpclient.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonSpansTest {
    @Test
    fun `colours keys strings numbers and keywords`() {
        val text = "{\n  \"a\": \"x\",\n  \"n\": -1.5e3,\n  \"t\": true,\n  \"z\": null\n}"
        val parts = jsonSpans(text).filter { it.part != JsonPart.PUNCT }.map { it.part to text.substring(it.start, it.end) }
        assertEquals(listOf(
            JsonPart.KEY to "\"a\"", JsonPart.STRING to "\"x\"",
            JsonPart.KEY to "\"n\"", JsonPart.NUMBER to "-1.5e3",
            JsonPart.KEY to "\"t\"", JsonPart.KEYWORD to "true",
            JsonPart.KEY to "\"z\"", JsonPart.KEYWORD to "null",
        ), parts)
    }

    @Test
    fun `survives unterminated strings and plain text`() {
        jsonSpans("\"abc")
        jsonSpans("not json at all")
    }
}
