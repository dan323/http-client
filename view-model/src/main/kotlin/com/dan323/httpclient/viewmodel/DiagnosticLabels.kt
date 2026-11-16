package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.Diagnostic
import com.dan323.httpclient.core.application.Severity

/** `Line 3: Undefined variable base`; lines are shown 1-based, the analysis counts from 0. */
fun problemLabel(d: Diagnostic): String = "Line ${d.range.line + 1}: ${d.message}"

/** `2 errors, 1 warning`; null when there is nothing to report. */
fun problemSummary(diagnostics: List<Diagnostic>): String? {
    val errors = diagnostics.count { it.severity == Severity.ERROR }
    val warnings = diagnostics.size - errors
    val parts = listOfNotNull(
        errors.takeIf { it > 0 }?.let { "$it ${if (it == 1) "error" else "errors"}" },
        warnings.takeIf { it > 0 }?.let { "$it ${if (it == 1) "warning" else "warnings"}" },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}
