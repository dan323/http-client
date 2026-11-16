package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.Diagnostic
import com.dan323.httpclient.core.application.Severity
import com.dan323.httpclient.core.application.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiagnosticLabelsTest {
    private fun d(severity: Severity, line: Int = 0, message: String = "m") =
        Diagnostic("c", severity, message, SourceRange(0, 1, line, 0))

    @Test fun labelShowsTheLineFromOne() = assertEquals("Line 3: Undefined variable base", problemLabel(d(Severity.ERROR, 2, "Undefined variable base")))

    @Test fun summaryCountsErrorsAndWarningsAndPluralises() {
        assertEquals("1 error", problemSummary(listOf(d(Severity.ERROR))))
        assertEquals("2 errors", problemSummary(listOf(d(Severity.ERROR), d(Severity.ERROR))))
        assertEquals("1 warning", problemSummary(listOf(d(Severity.WARNING))))
        assertEquals("2 warnings", problemSummary(listOf(d(Severity.WARNING), d(Severity.WARNING))))
        assertEquals("1 error, 2 warnings", problemSummary(listOf(d(Severity.WARNING), d(Severity.ERROR), d(Severity.WARNING))))
    }

    @Test fun noProblemsHasNoSummary() = assertNull(problemSummary(emptyList()))
}
