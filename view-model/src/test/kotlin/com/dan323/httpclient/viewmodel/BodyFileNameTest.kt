package com.dan323.httpclient.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals

class BodyFileNameTest {
    @Test fun contentDispositionWins() {
        assertEquals("report.pdf", suggestedFileName("http://x/a/b", "attachment; filename=\"report.pdf\"", "application/pdf"))
        assertEquals("n é.txt", suggestedFileName("http://x/a", "attachment; filename*=UTF-8''n%20%C3%A9.txt", null))
        assertEquals("plain.txt", suggestedFileName("http://x/a", "attachment; filename=plain.txt", null))
    }

    @Test fun fallsBackToTheUrlSegmentThenTheContentType() {
        assertEquals("data.json", suggestedFileName("http://x/api/data.json?a=1#f", null, "application/json"))
        assertEquals("users.json", suggestedFileName("http://x/api/users", null, "application/json; charset=utf-8"))
        assertEquals("response.html", suggestedFileName("http://x/", null, "text/html"))
        assertEquals("response.bin", suggestedFileName("http://x", null, null))
        assertEquals("a b.xml", suggestedFileName("http://x/a%20b", null, "application/atom+xml"))
    }

    @Test fun extensionsPerType() {
        val types = mapOf("text/csv" to ".csv", "text/plain" to ".txt", "image/png" to ".png", "image/jpeg" to ".jpg", "image/gif" to ".gif",
            "application/pdf" to ".pdf", "text/xml" to ".xml", "application/octet-stream" to ".bin")
        for ((type, ext) in types) assertEquals("response$ext", suggestedFileName("http://x/", null, type))
    }

    @Test fun unsafeNamesAreCleaned() {
        assertEquals("evil.txt", suggestedFileName("http://x/", "attachment; filename=\"../../evil.txt\"", null).substringAfterLast("_"))
        assertEquals("hidden", suggestedFileName("http://x/", "attachment; filename=\".hidden\"", null))
        assertEquals("response", suggestedFileName("http://x/", "attachment; filename=\"...\"", null))
        assertEquals("ab", suggestedFileName("http://x/", "attachment; filename=\"a\u0001b\"", null))
        assertEquals(100, suggestedFileName("http://x/", "attachment; filename=\"${"a".repeat(300)}\"", null).length)
        assertEquals("C__x.txt", suggestedFileName("http://x/", "attachment; filename=\"C:\\x.txt\"", null))
    }
}
