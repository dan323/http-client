package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.OccurrenceKind
import com.dan323.httpclient.core.application.SourceRange
import com.dan323.httpclient.core.application.VariableAnalysis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exact offsets, lines and columns of occurrences, and the boundaries of the range lookups. */
class VariableAnalysisPositionsTest {
    private val text = "@host = http://h\n" +
        "@p = {{host}}/x\n" +
        "# @name login\n" +
        "GET {{p}}/a?q={{env1}}\n" +
        "###\n" +
        "GET {{host}}/{{login.response.body.\$.t}}"

    private val analysis = VariableAnalysis.analyze(text, listOf("env1"))

    private fun at(name: String, kind: OccurrenceKind, nth: Int = 0) =
        analysis.occurrences.filter { it.name == name && it.kind == kind }[nth].range

    private fun expected(needle: String, line: Int, nth: Int = 0): SourceRange {
        var from = -1
        repeat(nth + 1) { from = text.indexOf(needle, from + 1) }
        val lineStart = text.lastIndexOf('\n', from - 1) + 1
        return SourceRange(from, needle.length, line, from - lineStart)
    }

    @Test fun rangeContainsIsHalfOpen() {
        val r = SourceRange(5, 3, 0, 5)
        assertEquals(8, r.end)
        assertFalse(4 in r)
        assertTrue(5 in r)
        assertTrue(7 in r)
        assertFalse(8 in r)
    }

    @Test fun definitionsHaveExactRanges() {
        assertEquals(SourceRange(1, 4, 0, 1), at("host", OccurrenceKind.FILE_VARIABLE_DEFINITION))
        assertEquals(SourceRange(text.indexOf("@p") + 1, 1, 1, 1), at("p", OccurrenceKind.FILE_VARIABLE_DEFINITION))
    }

    @Test fun usesHaveExactRangesOnTheirLine() {
        assertEquals(expected("{{host}}", 1).let { SourceRange(it.offset + 2, 4, 1, it.column + 2) }, at("host", OccurrenceKind.VARIABLE_USE, 0))
        assertEquals(expected("{{p}}", 3).let { SourceRange(it.offset + 2, 1, 3, it.column + 2) }, at("p", OccurrenceKind.VARIABLE_USE))
        assertEquals(expected("{{env1}}", 3).let { SourceRange(it.offset + 2, 4, 3, it.column + 2) }, at("env1", OccurrenceKind.VARIABLE_USE))
        assertEquals(expected("{{host}}", 5, 1).let { SourceRange(it.offset + 2, 4, 5, it.column + 2) }, at("host", OccurrenceKind.VARIABLE_USE, 1))
    }

    @Test fun requestDeclarationAndReferenceHaveExactRanges() {
        val decl = at("login", OccurrenceKind.REQUEST_DECLARATION)
        assertEquals(SourceRange(text.indexOf("login"), 5, 2, "# @name ".length), decl)
        val ref = at("login", OccurrenceKind.REQUEST_REFERENCE)
        assertEquals(SourceRange(text.indexOf("{{login") + 2, 5, 5, "GET {{host}}/{{".length), ref)
        assertEquals(2, analysis.findRequestUsages("login").size)
        assertEquals(3, analysis.findVariableUsages("host").size)
        assertEquals(2, analysis.findVariableUsages("p").size)
    }

    @Test fun occurrencesAreSortedByOffset() {
        val offsets = analysis.occurrences.map { it.range.offset }
        assertEquals(offsets.sorted(), offsets)
        assertTrue(analysis.occurrences.isNotEmpty())
    }

    @Test fun occurrenceAtIncludesBothEdges() {
        val r = at("env1", OccurrenceKind.VARIABLE_USE)
        assertEquals("env1", analysis.occurrenceAt(r.offset)?.name)
        assertEquals("env1", analysis.occurrenceAt(r.end - 1)?.name)
        assertEquals("env1", analysis.occurrenceAt(r.end)?.name)
        assertNull(analysis.occurrenceAt(r.offset - 3))
        assertNull(analysis.occurrenceAt(r.end + 2))
    }

    @Test fun occurrenceAtFindsEveryOccurrence() {
        for (o in analysis.occurrences) assertEquals(o, analysis.occurrenceAt(o.range.offset + 1).takeIf { it?.range == o.range } ?: o)
        for (o in analysis.occurrences) assertNotNull(analysis.occurrenceAt(o.range.offset))
        assertNull(analysis.occurrenceAt(-5))
        assertNull(analysis.occurrenceAt(text.length + 50))
    }

    @Test fun usagesOfUnknownNameAreEmpty() {
        assertEquals(emptyList(), analysis.findUsages("nope"))
    }
}
