package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.DiagnosticCodes
import com.dan323.httpclient.core.application.Diagnostic
import com.dan323.httpclient.core.application.OccurrenceKind
import com.dan323.httpclient.core.application.Severity
import com.dan323.httpclient.core.application.VariableAnalysis
import com.dan323.httpclient.core.application.VariableKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VariableAnalysisTest {
    private fun analyze(text: String, vararg env: String) = VariableAnalysis.analyze(text, env.toList())
    private fun codes(text: String, vararg env: String) = analyze(text, *env).diagnostics.map { it.code }
    private fun slice(text: String, d: Diagnostic) = text.substring(d.range.offset, d.range.end)

    @Test fun cleanFileHasNoDiagnostics() {
        val text = "@h = http://x\n# @name a\nGET {{h}}/{{base}}/{{\$uuid}}\n###\nGET {{h}}?t={{a.response.body.\$.t}}&s={{a.response.status}}&h={{a.response.headers.X-Id}}"
        assertEquals(emptyList(), analyze(text, "base").diagnostics)
    }

    @Test fun undefinedVariableHasExactRange() {
        val text = "GET http://h/{{nope}}/ok"
        val d = analyze(text).diagnostics.single()
        assertEquals(DiagnosticCodes.UNDEFINED_VARIABLE, d.code)
        assertEquals(Severity.ERROR, d.severity)
        assertEquals("nope", slice(text, d))
        assertEquals(0, d.range.line)
        assertEquals(text.indexOf("nope"), d.range.column)
        assertEquals("Undefined variable: nope", d.message)
    }

    @Test fun variablesInHeadersBodyAssertionsAndSettings() {
        val text = """
            # @timeout {{t1}}
            # @assert body.${'$'}.a == {{a1}} # {{in-comment}}
            # @ignored {{not-resolved}}
            # plain comment {{c}}
            POST http://h/{{u}}
            X-{{headername}}: {{hv}}
            ?x={{notcontinuation}}

            {"k": "{{b1}}"}
        """.trimIndent()
        // the `?x=` line is not a continuation here (headers section), it is a header-less line: the parser rejects it
        val fixed = text.replace("?x={{notcontinuation}}\n", "")
        val names = analyze(fixed).diagnostics.map { slice(fixed, it) }
        assertEquals(listOf("t1", "a1", "u", "hv", "b1"), names)
    }

    @Test fun fileBodyPathIsScannedButNotItsContents() {
        val text = "POST http://h\n\n< ./{{dir}}/payload.json"
        assertEquals(listOf("dir"), analyze(text).diagnostics.map { slice(text, it) })
    }

    @Test fun trailingHandlersAreNotScanned() {
        val text = "GET http://h\n\n> {% client.global.set(\"a\", \"{{x}}\") %}\n<> {{out}}.json"
        assertEquals(emptyList(), analyze(text).diagnostics)
    }

    @Test fun environmentAndFileVariablesAreKnown() {
        val text = "@f = 1\nGET http://h/{{f}}/{{e}}"
        assertEquals(emptyList(), analyze(text, "e").diagnostics)
        assertEquals(listOf("e"), analyze(text).diagnostics.map { slice(text, it) })
    }

    @Test fun fileVariableBeforeUseOnly() {
        val text = "GET http://h/{{late}}\n###\n@late = 1\nGET http://h/{{late}}"
        val d = analyze(text).diagnostics.single()
        assertEquals(0, d.range.line)
    }

    @Test fun fileVariableValuesAreChecked() {
        val text = "@a = {{missing}}-{{env1}}\nGET http://h/{{a}}"
        assertEquals(listOf("missing"), analyze(text, "env1").diagnostics.map { slice(text, it) })
    }

    @Test fun selfReferenceReadsEnvironment() {
        val text = "@host = {{host}}/api\nGET {{host}}"
        assertEquals(emptyList(), analyze(text, "host").diagnostics)
        assertEquals(listOf(DiagnosticCodes.UNDEFINED_VARIABLE), codes(text))
    }

    @Test fun laterFileVariableInValueWarns() {
        val text = "@a = {{b}}\n@b = 1\nGET http://h/{{a}}"
        val d = analyze(text).diagnostics.single()
        assertEquals(DiagnosticCodes.VARIABLE_DEFINED_LATER, d.code)
        assertEquals(Severity.WARNING, d.severity)
    }

    @Test fun cyclicFileVariables() {
        val text = "@a = {{b}}\n@b = {{a}}\nGET http://h/{{a}}"
        val ds = analyze(text).diagnostics.filter { it.code == DiagnosticCodes.CIRCULAR_VARIABLE }
        assertEquals(2, ds.size)
        assertTrue(ds.all { it.message == "Circular variable reference: a -> b -> a" || it.message == "Circular variable reference: b -> a -> b" })
        // an environment variable of the same name breaks the cycle just like at run time
        assertTrue(analyze("@a = {{a}}\nGET http://h/{{a}}", "a").diagnostics.isEmpty())
    }

    @Test fun cycleViaSelfThroughOthers() {
        val text = "@a = {{b}}\n@b = {{c}}\n@c = {{a}}\nGET {{a}}"
        assertEquals(3, analyze(text).diagnostics.count { it.code == DiagnosticCodes.CIRCULAR_VARIABLE })
    }

    @Test fun responseReferences() {
        val text = "# @name first\nGET http://h/\n###\n# @name second\nGET http://h/{{first.response.status}}/{{second.response.status}}/{{third.response.status}}/{{ghost.response.status}}\n###\n# @name third\nGET http://h"
        val ds = analyze(text).diagnostics
        assertEquals(
            listOf(DiagnosticCodes.SELF_REFERENCE, DiagnosticCodes.REQUEST_NOT_RUN_YET, DiagnosticCodes.UNKNOWN_REQUEST).sorted(),
            ds.map { it.code }.sorted(),
        )
        assertEquals(setOf("second", "third", "ghost"), ds.map { slice(text, it) }.toSet())
    }

    @Test fun requestNamesWithDots() {
        val text = "# @name auth.login\nGET http://h\n###\nGET http://h/{{auth.login.response.status}}"
        assertEquals(emptyList(), analyze(text).diagnostics)
        val usages = analyze(text).findRequestUsages("auth.login")
        assertEquals(listOf(OccurrenceKind.REQUEST_DECLARATION, OccurrenceKind.REQUEST_REFERENCE), usages.map { it.kind })
        assertEquals("auth.login", text.substring(usages[1].range.offset, usages[1].range.end))
    }

    @Test fun malformedResponseReferences() {
        val base = "# @name r\nGET http://h\n###\nGET http://h/"
        fun bad(ref: String) = analyze("$base{{$ref}}").diagnostics.map { it.code }
        assertEquals(listOf(DiagnosticCodes.MALFORMED_REFERENCE), bad("r.response."))
        assertEquals(listOf(DiagnosticCodes.MALFORMED_REFERENCE), bad("r.response"))
        assertEquals(listOf(DiagnosticCodes.MALFORMED_REFERENCE), bad("r.response.nothing"))
        assertEquals(listOf(DiagnosticCodes.MALFORMED_REFERENCE), bad("r.response.body."))
        assertEquals(listOf(DiagnosticCodes.MALFORMED_REFERENCE), bad("r.response.headers."))
        assertEquals(emptyList(), bad("r.response.body.*"))
        assertEquals(emptyList(), bad("r.response.body./a/b"))
    }

    @Test fun unterminatedAndInvalidBraces() {
        val text = "GET http://h/{{oops\nX: {{ a b }}\n"
        val ds = analyze(text).diagnostics
        assertEquals(listOf(DiagnosticCodes.UNTERMINATED_REFERENCE, DiagnosticCodes.INVALID_REFERENCE_TEXT), ds.map { it.code })
        assertEquals(2, ds.first().range.length)
        val body = "POST http://h\n\n{{ a b }}"
        assertEquals(listOf(DiagnosticCodes.INVALID_REFERENCE_TEXT), codes(body))
        // `{{{x}}`: the first brace is plain text, the reference is `{{x}}`
        assertEquals(listOf("x"), analyze("POST http://h\n\n{{{x}}").diagnostics.map { slice("POST http://h\n\n{{{x}}", it) })
    }

    @Test fun dynamicVariables() {
        val ok = "GET http://h/{{\$uuid}}/{{\$timestamp 1 d}}/{{\$random.integer(1, 5)}}/{{\$env.ANYTHING}}/{{\$processEnv X}}"
        assertEquals(emptyList(), analyze(ok).diagnostics)
        assertEquals(listOf(DiagnosticCodes.UNDEFINED_VARIABLE), codes("GET http://h/{{\$nope}}"))
        val invalid = analyze("GET http://h/{{\$random.integer(5, 1)}}").diagnostics.single()
        assertEquals(DiagnosticCodes.INVALID_DYNAMIC_VARIABLE, invalid.code)
    }

    @Test fun messagesNeverContainValues() {
        val text = "@secret = hunter2\n@a = {{secret}}{{missing}}\nGET http://h/{{a}}"
        val a = VariableAnalysis.analyze(text, listOf("token"))
        assertTrue(a.diagnostics.isNotEmpty())
        assertTrue(a.diagnostics.none { "hunter2" in it.message })
    }

    @Test fun duplicateRequestNames() {
        val text = "# @name a\nGET http://h\n###\n# @name a\nGET http://h"
        val d = analyze(text).diagnostics.single()
        assertEquals(DiagnosticCodes.DUPLICATE_REQUEST_NAME, d.code)
        assertEquals(3, d.range.line)
    }

    @Test fun crlfTabsAndUnicodeRanges() {
        val text = "@v = 1\r\n# @name r\r\nGET http://h/\u00e9\uD83D\uDE00/{{nope}}\r\n\tX-A:\t{{nope2}}\r\n"
        val ds = analyze(text).diagnostics
        assertEquals(listOf("nope", "nope2"), ds.map { slice(text, it) })
        assertEquals(2, ds[0].range.line)
        assertEquals("GET http://h/\u00e9\uD83D\uDE00/{{".length, ds[0].range.column) // UTF-16 chars: the emoji counts 2
        assertEquals(3, ds[1].range.line)
        assertEquals("\tX-A:\t{{".length, ds[1].range.column)
    }

    @Test fun multiLineReferenceInBody() {
        val text = "POST http://h\n\n{{\n  nope\n}}"
        val d = analyze(text).diagnostics.single()
        assertEquals("nope", slice(text, d))
        assertEquals(3, d.range.line)
        assertEquals(2, d.range.column)
    }

    @Test fun parseErrorIsReported() {
        val d = analyze("GTE http://h").diagnostics.single()
        assertEquals(DiagnosticCodes.PARSE_ERROR, d.code)
    }

    @Test fun responseReferencesThatWorkInALaterSessionAreWarnings() {
        val text = "# @name poll\nGET http://h/{{poll.response.body.\$.next}}\n###\nGET http://h/{{late.response.status}}\n###\n# @name late\nGET http://h"
        val ds = analyze(text).diagnostics
        assertEquals(setOf(DiagnosticCodes.SELF_REFERENCE, DiagnosticCodes.REQUEST_NOT_RUN_YET), ds.map { it.code }.toSet())
        assertTrue(ds.all { it.severity == Severity.WARNING })
    }

    @Test fun aBadRequestKeepsTheRestOfTheFileAnalysed() {
        val text = "@base = http://h\n# @name one\nGET {{base}}/a\n###\nGTE {{base}}/typo\n###\n# @name three\nGET {{base}}/{{one.response.status}}/{{nope}}"
        val a = analyze(text, "env1")
        val ds = a.diagnostics
        assertEquals(setOf(DiagnosticCodes.PARSE_ERROR, DiagnosticCodes.UNDEFINED_VARIABLE), ds.map { it.code }.toSet())
        assertEquals(4, ds.single { it.code == DiagnosticCodes.PARSE_ERROR }.range.line) // the bad line only
        assertEquals("nope", slice(text, ds.single { it.code == DiagnosticCodes.UNDEFINED_VARIABLE }))
        // find-usages, go-to-definition and completion still work everywhere
        assertEquals(3, a.findVariableUsages("base").size) // the definition and the two uses outside the bad request
        val names = a.knownVariablesAt(text.length).map { it.name }
        assertTrue("base" in names && "env1" in names && "one.response." in names)
    }

    @Test fun anUnsupportedProtocolBlockIsAParseErrorAndTheRestIsStillAnalysed() {
        val text = "@a = 1\nGET http://h/{{a}}\n###\nGRPC localhost:50051/{{a}}\n###\nGET http://h/{{b}}"
        val a = analyze(text)
        val ds = a.diagnostics
        assertEquals(setOf(DiagnosticCodes.PARSE_ERROR, DiagnosticCodes.UNDEFINED_VARIABLE), ds.map { it.code }.toSet())
        val pe = ds.single { it.code == DiagnosticCodes.PARSE_ERROR }
        assertEquals(3, pe.range.line)
        assertTrue("GRPC requests are not supported" in pe.message)
        assertEquals(1, a.findUsages("b").size)
    }

    @Test fun anUnsupportedProtocolBlockWithHeadersAndBodyGivesOneParseErrorAndKeepsLaterBlocks() {
        val text = "@a = 1\nGRPC localhost:50051/{{a}}\nContent-Type: application/json\n\n{\"x\": \"{{a}}\"}\n###\nGET http://h/{{a}}/{{b}}"
        val a = analyze(text)
        val ds = a.diagnostics
        // the header line after the rejected line becomes the next request line and is rejected too, but only one error per block is reported
        assertEquals(1, ds.count { it.code == DiagnosticCodes.PARSE_ERROR })
        assertEquals(1, ds.single { it.code == DiagnosticCodes.PARSE_ERROR }.range.line)
        assertEquals(1, ds.count { it.code == DiagnosticCodes.UNDEFINED_VARIABLE })
        assertEquals("b", slice(text, ds.single { it.code == DiagnosticCodes.UNDEFINED_VARIABLE }))
        assertEquals(3, a.findVariableUsages("a").size) // the definition, the leftover body line of the rejected block (read as a request of its own) and the later block
    }

    @Test fun anIncompleteRequestLineDoesNotBlankTheFile() {
        val text = "@a = 1\nGET\n###\nGET http://h/{{a}}/{{b}}"
        val a = analyze(text)
        assertEquals(setOf(DiagnosticCodes.PARSE_ERROR, DiagnosticCodes.UNDEFINED_VARIABLE), a.diagnostics.map { it.code }.toSet())
        assertEquals(2, a.findVariableUsages("a").size)
        assertEquals(1, a.findUsages("b").size)
    }

    @Test fun oneParseErrorPerBlockEvenWhenSeveralLinesAreBad() {
        val text = "GTE http://h\nContent-Type text/plain\nAnother header\n###\nGET http://h/{{x}}"
        val ds = analyze(text).diagnostics
        assertEquals(1, ds.count { it.code == DiagnosticCodes.PARSE_ERROR })
        assertEquals(1, ds.count { it.code == DiagnosticCodes.UNDEFINED_VARIABLE })
    }

    @Test fun parseErrorRangesAndOffsetsUseTheOriginalText() {
        val text = "GET http://h/{{ok}}\r\n###\r\nGTE http://h\r\n###\r\nGET http://h/{{late}}"
        val ds = analyze(text, "ok").diagnostics
        val error = ds.single { it.code == DiagnosticCodes.PARSE_ERROR }
        assertEquals("GTE http://h", slice(text, error))
        assertEquals(2, error.range.line)
        assertEquals("late", slice(text, ds.single { it.code == DiagnosticCodes.UNDEFINED_VARIABLE }))
    }

    @Test fun aFileWithManyBadLinesFallsBackToParseErrorsOnly() {
        val text = (1..200).joinToString("\n###\n") { "GTE http://h/$it" }
        val a = analyze(text)
        assertTrue(a.diagnostics.isNotEmpty() && a.diagnostics.all { it.code == DiagnosticCodes.PARSE_ERROR })
        assertTrue(a.occurrences.isEmpty())
    }

    @Test fun aBadLineInsideAFileVariableBlockKeepsTheDefinition() {
        val text = "@v = 1\nGTE x\nGET http://h/{{v}}"
        val a = analyze(text)
        assertEquals(2, a.findVariableUsages("v").size)
    }

    @Test fun savedResponsePathIsScanned() {
        val text = "# @name one\nGET http://h\n\n>> out/{{one.response.status}}-{{nope}}.json\n###\nGET http://h\n\n>>! {{dir}}/x.json\n<> {{notResolved}}.json"
        val a = analyze(text, "dir")
        val undefined = a.diagnostics.filter { it.code == DiagnosticCodes.UNDEFINED_VARIABLE }
        assertEquals(listOf("nope"), undefined.map { slice(text, it) }) // links are not resolved, so {{notResolved}} is not flagged
        assertEquals(1, a.findVariableUsages("dir").size)
        assertEquals(1, a.findRequestUsages("one").count { it.kind == OccurrenceKind.REQUEST_REFERENCE })
    }

    @Test fun findUsages() {
        val text = "@x = 1\nGET http://h/{{x}}\n###\nGET http://h/{{x}}?{{y}}"
        val a = analyze(text)
        val usages = a.findVariableUsages("x")
        assertEquals(listOf(OccurrenceKind.FILE_VARIABLE_DEFINITION, OccurrenceKind.VARIABLE_USE, OccurrenceKind.VARIABLE_USE), usages.map { it.kind })
        assertTrue(usages.all { text.substring(it.range.offset, it.range.end) == "x" })
        assertEquals(1, a.findUsages("y").size) // undefined variables can be found too
        assertEquals(usages[1], a.occurrenceAt(usages[1].range.offset + 1))
        assertNull(a.occurrenceAt(0).takeIf { it?.name == "zzz" })
    }

    @Test fun knownVariablesAtPoint() {
        val text = "@f = 1\n# @name one\nGET http://h/\n###\n@g = 2\n# @name two\nGET http://h/{{}}\n###\n@late = 3\nGET http://h/"
        val a = analyze(text, "env1")
        val at = a.knownVariablesAt(text.indexOf("{{}}") + 2)
        val byName = at.associateBy { it.name }
        assertEquals(VariableKind.FILE, byName.getValue("f").kind)
        assertEquals(VariableKind.FILE, byName.getValue("g").kind)
        assertEquals(VariableKind.ENVIRONMENT, byName.getValue("env1").kind)
        assertEquals(VariableKind.REQUEST_RESPONSE, byName.getValue("one.response.").kind)
        assertNull(byName["two.response."]) // the request itself
        assertNull(byName["late"])
        assertNotNull(byName["\$uuid"])
        assertEquals(at.size, at.map { it.name }.toSet().size)
    }

    @Test fun largeFileIsLinear() {
        val sb = StringBuilder()
        repeat(20_000) { sb.append("# @name r$it\nGET http://h/{{base}}/{{\$uuid}}/{{nope$it}}\nX: {{{{{{\n###\n") }
        val start = System.nanoTime()
        val a = VariableAnalysis.analyze(sb.toString(), listOf("base"))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(a.diagnostics.size >= 20_000)
        assertTrue(ms < 20_000, "took $ms ms")
    }
}
