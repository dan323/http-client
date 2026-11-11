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
        fun header(name: String) = suggestedFileName("http://x/", "attachment; filename=\"$name\"", null)
        assertEquals("_.._evil.txt", header("../../evil.txt"))   // no separator survives, the leading dots go
        assertEquals("hidden", header(".hidden"))
        assertEquals("response.bin", header("..."))
        assertEquals("ab", header("a\u0001b"))
        assertEquals("C__x.txt", header("C:\\x.txt"))
        assertEquals("report_.pdf", header("report?.pdf"))
        assertEquals("a_b_d_e_f.txt", header("a<b>d|e*f.txt"))
        assertEquals("name", header("name. . "))
    }

    @Test fun lengthIncludesTheExtensionAndTheHeaderIsParsedWithQuotes() {
        assertEquals(100, header300().length)
        val fromUrl = suggestedFileName("http://x/" + "a".repeat(150), null, "application/json")
        assertEquals(100, fromUrl.length)
        assertEquals(true, fromUrl.endsWith(".json"))
        assertEquals("a;b.txt", suggestedFileName("http://x/", "attachment; filename=\"a;b.txt\"; size=3", null))
        assertEquals("q_x.txt", suggestedFileName("http://x/", "attachment; filename=\"q\\\"x.txt\"", null))
    }

    @Test fun theLanguageFieldOfAnExtendedNameIsSkipped() {
        assertEquals("report.txt", suggestedFileName("http://x/", "attachment; filename*=UTF-8'en'report.txt", null))
        assertEquals("r é.txt", suggestedFileName("http://x/", "attachment; filename*=UTF-8'en-GB'r%20%C3%A9.txt", null))
    }

    @Test fun reservedWindowsNamesGetAPrefix() {
        assertEquals("_NUL.txt", suggestedFileName("http://x/", "attachment; filename=\"NUL.txt\"", null))
        assertEquals("_com1", suggestedFileName("http://x/", "attachment; filename=\"com1\"", null))
        assertEquals("_Lpt9.bin", suggestedFileName("http://x/Lpt9", null, null))
        assertEquals("console.txt", suggestedFileName("http://x/", "attachment; filename=\"console.txt\"", null))
    }

    @Test fun anUnresolvedVariableIsNotAName() {
        assertEquals("response.json", suggestedFileName("{{host}}/files/{{id}}", null, "application/json"))
        assertEquals("files.json", suggestedFileName("{{host}}/files", null, "application/json"))
    }

    private fun header300() = suggestedFileName("http://x/", "attachment; filename=\"${"a".repeat(300)}\"", null)
}
