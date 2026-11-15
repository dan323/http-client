package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.DiagnosticCodes
import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.HttpLineLexer
import com.dan323.httpclient.core.application.HttpParseException
import com.dan323.httpclient.core.application.HttpTokenKind
import com.dan323.httpclient.core.application.HttpTokenKind.*
import com.dan323.httpclient.core.application.MultipartParser
import com.dan323.httpclient.core.application.VariableAnalysis
import com.dan323.httpclient.core.domain.Body
import com.dan323.httpclient.core.domain.PartContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Indented lines, lines that merely end with `###`, unnamed requests and the other inputs the Kotlin mutators (`?:`, `?.`, `trimStart`, `indexOf`) change the result for. */
class NullPathsParsingTest {
    private fun kinds(text: String) =
        HttpLineLexer.tokenize(text).filter { it.kind != WHITESPACE }.map { it.kind to text.substring(it.start, it.end) }

    // HttpLineLexer
    @Test fun `a line that only ends with the separator is not a separator`() {
        assertFalse(HttpLineLexer.tokenize("GET /x ###\n").any { it.kind == SEPARATOR })
        assertEquals(listOf(SEPARATOR to "###abc"), kinds("###abc\n"))
    }

    @Test fun `indented file variables, request lines, query lines and saves are tokenized from their first character`() {
        assertEquals(
            listOf(HEADER_NAME to "@a", COLON to "=", HEADER_VALUE to " 1", METHOD to "GET", URL to "http://x", URL to "?q=1"),
            kinds("  @a = 1\n  GET http://x\n  ?q=1\n"),
        )
        assertEquals(
            listOf(METHOD to "POST", URL to "/x", BODY_TEXT to "body", COMMENT to ">>", FILE_REF to "out.txt"),
            kinds("POST /x\n\nbody\n  >> out.txt\n"),
        )
    }

    // HttpFileParser
    private val parser = HttpFileParser()

    @Test fun `an unknown method with nothing close to it has no suggestion`() {
        val e = assertFailsWith<HttpParseException> { parser.parse("FROBNICATE http://x\n") }
        assertTrue("unknown HTTP method 'FROBNICATE'" in e.message!!, e.message)
        assertTrue(e.message!!.endsWith("unknown HTTP method 'FROBNICATE'"), e.message)
    }

    @Test fun `a body without a Content-Type header stays text`() {
        assertEquals(Body.Text("hello"), parser.parse("POST http://x\n\nhello\n").requests.single().body)
        assertEquals(Body.Text("a\nb"), parser.parse("POST http://x\nAccept: */*\n\na\nb\n").requests.single().body)
    }

    // MultipartParser
    @Test fun `a multipart type without a boundary has none, and other types are not multipart`() {
        assertNull(MultipartParser.boundaryOf("multipart/form-data"))
        assertNull(MultipartParser.boundaryOf("text/plain; boundary=x"))
        assertEquals("B", MultipartParser.boundaryOf("multipart/form-data; boundary=B"))
        assertEquals("a b", MultipartParser.boundaryOf("multipart/form-data; boundary=\"a b\""))
    }

    @Test fun `delimiter lines with trailing whitespace and an empty part are accepted`() {
        val text = "POST http://x\nContent-Type: multipart/form-data; boundary=B\n\n--B  \nContent-Disposition: form-data; name=\"a\"\n\n--B--  \n"
        val body = parser.parse(text).requests.single().body as Body.Multipart
        assertEquals(1, body.parts.size)
        assertEquals(PartContent.Text(""), body.parts.single().content)
    }

    // VariableAnalysis
    private fun analyze(text: String) = VariableAnalysis.analyze(text, emptyList())
    private fun slice(text: String, d: com.dan323.httpclient.core.application.Diagnostic) = text.substring(d.range.offset, d.range.end)

    @Test fun `ranges of indented definitions, request lines and references start at their first character`() {
        val text = "  @a = 1\n  # @name r\n  GET http://h/{{a}}\n    ?q={{a}}\n  X: v\n    {{a}}\n"
        val a = analyze(text)
        assertEquals(emptyList(), a.diagnostics)
        val usages = a.findUsages("a")
        assertEquals(4, usages.size)
        usages.forEach { assertEquals("a", text.substring(it.range.offset, it.range.end)) }
        val declared = a.findUsages("r").single()
        assertEquals("r", text.substring(declared.range.offset, declared.range.end))
    }

    @Test fun `a variable in an indented file reference or save line is found where it is written`() {
        val file = "@a = 1\n  GET http://h/\n\n  < {{nope}}.json\n"
        assertEquals("nope", analyze(file).diagnostics.single().let { slice(file, it) })
        val save = "GET http://h/\n\nb\n  >> {{nope}}.out\n"
        assertEquals("nope", analyze(save).diagnostics.single().let { slice(save, it) })
    }

    @Test fun `a continuation header line that starts with whitespace and ends with a letter is scanned`() {
        val text = "GET http://h/\nX: v\n  {{nope}}b\n"
        assertEquals("nope", analyze(text).diagnostics.single().let { slice(text, it) })
    }

    @Test fun `a line that only ends with the separator does not split the request`() {
        val text = "GET http://h/\nX: a ###\nY: {{nope}}\n"
        val d = analyze(text).diagnostics.single()
        assertEquals(DiagnosticCodes.UNDEFINED_VARIABLE, d.code)
        assertEquals("nope", slice(text, d))
    }

    @Test fun `each unterminated brace pair is reported and a closing pair elsewhere does not hide it`() {
        val text = "GET http://h/\n\nbody {{ a\nc {{ d\n"
        val ds = analyze(text).diagnostics
        assertEquals(listOf(DiagnosticCodes.UNTERMINATED_REFERENCE, DiagnosticCodes.UNTERMINATED_REFERENCE), ds.map { it.code })
        assertEquals(listOf("{{", "{{"), ds.map { slice(text, it) })
        val mixed = "GET http://h/\n\n{{ q }} }} x\n{{ b\n"
        val m = analyze(mixed).diagnostics
        assertEquals(listOf(DiagnosticCodes.UNDEFINED_VARIABLE, DiagnosticCodes.UNTERMINATED_REFERENCE), m.map { it.code })
        assertEquals(listOf("q", "{{"), m.map { slice(mixed, it) })
    }

    @Test fun `an unknown response part is quoted up to 40 characters and the first response marker splits the name`() {
        val text = "# @name a\nGET http://h/{{a.response.${"x".repeat(50)}}}\n"
        val d = analyze(text).diagnostics.single { it.code == DiagnosticCodes.MALFORMED_REFERENCE }
        assertEquals("Unknown response part '${"x".repeat(40)}' (expected status, headers.<Name> or body...)", d.message)
        val twice = "# @name a\nGET http://h/{{a.response.body.response.x}}\n###\nGET http://h/{{a.response.status}}\n"
        assertTrue(analyze(twice).diagnostics.none { it.code == DiagnosticCodes.MALFORMED_REFERENCE })
    }

    @Test fun `an invalid dynamic variable is reported with the reason`() {
        val d = analyze("GET http://h/{{\$randomInt abc}}\n").diagnostics.single()
        assertEquals(DiagnosticCodes.INVALID_DYNAMIC_VARIABLE, d.code)
        assertTrue(d.message.startsWith("Invalid dynamic variable {{\$randomInt abc}}: expected \$randomInt"), d.message)
    }

    @Test fun `requests without a name are skipped where names are collected`() {
        val text = "GET http://h/\n###\n# @name b\nGET http://h/\n###\nGET http://h/{{b.response.status}}\n"
        val a = analyze(text)
        assertEquals(emptyList(), a.diagnostics)
        assertTrue(a.knownVariablesAt(text.length - 1).none { it.name == "null" })
        assertEquals(2, a.findRequestUsages("b").size)   // the declaration and the reference
    }
}
