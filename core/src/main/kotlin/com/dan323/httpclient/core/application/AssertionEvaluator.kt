package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.HttpResponse

/** Result of one `# @assert` line. [expected] and [actual] are null when not applicable (e.g. `exists`, or a missing value). */
data class AssertionResult(
    val assertion: String,
    val passed: Boolean,
    val expected: String?,
    val actual: String?,
    val message: String,
)

/**
 * Evaluates assertions of the form `<expression> <operator> [expected]` against a response.
 * `<expression>` is anything [ResponseExtractor] understands and must not contain whitespace
 * (use `\s` in regexes); `expected` is the rest of the line, trimmed.
 *
 * Operators: `==`, `!=`, `<`, `<=`, `>`, `>=` (numeric when both sides are numbers, else text for `==`/`!=`),
 * `contains`, `!contains`, `startsWith`, `endsWith`, `matches` (whole-value regex), `exists`, `!exists`.
 */
class AssertionEvaluator(private val extractor: ResponseExtractor) {

    fun evaluate(assertions: List<String>, response: HttpResponse): List<AssertionResult> =
        assertions.map { evaluate(it, response) }

    fun evaluate(assertion: String, response: HttpResponse): AssertionResult {
        val parts = assertion.trim().split(Regex("\\s+"), limit = 3)
        val expr = parts[0]
        val op = parts.getOrNull(1) ?: return invalid(assertion, "missing operator")
        val expected = parts.getOrNull(2)?.trim()
        val actual = extractor.extract(response, expr)

        fun result(passed: Boolean, exp: String?) = AssertionResult(
            assertion, passed, exp, actual,
            if (passed) "OK" else "expected $expr ${describe(op)}${exp?.let { " $it" } ?: ""}, but was ${actual ?: "missing"}",
        )

        when (op) {
            "exists" -> return result(actual != null, null)
            "!exists" -> return result(actual == null, null)
        }
        if (op !in VALUE_OPERATORS) return invalid(assertion, "unknown operator '$op'")
        if (expected.isNullOrEmpty()) return invalid(assertion, "operator '$op' needs an expected value")
        if (actual == null) return result(op == "!=" || op == "!contains", expected)

        val a = actual.toDoubleOrNull()
        val e = expected.toDoubleOrNull()
        val passed = when (op) {
            "==" -> if (a != null && e != null) a == e else actual == expected
            "!=" -> if (a != null && e != null) a != e else actual != expected
            "<", "<=", ">", ">=" -> {
                if (a == null || e == null) return invalid(assertion, "'$op' needs numbers (actual: $actual, expected: $expected)", actual)
                when (op) { "<" -> a < e; "<=" -> a <= e; ">" -> a > e; else -> a >= e }
            }
            "contains" -> actual.contains(expected)
            "!contains" -> !actual.contains(expected)
            "startsWith" -> actual.startsWith(expected)
            "endsWith" -> actual.endsWith(expected)
            else -> runCatching { Regex(expected).matches(actual) }
                .getOrElse { return invalid(assertion, "bad regex: ${it.message}", actual) }
        }
        return result(passed, expected)
    }

    private fun describe(op: String) = when (op) {
        "exists" -> "to exist"
        "!exists" -> "not to exist"
        "==" -> "to equal"
        "!=" -> "not to equal"
        else -> op
    }

    private fun invalid(assertion: String, why: String, actual: String? = null) =
        AssertionResult(assertion, false, null, actual, "invalid assertion: $why")

    private companion object {
        val VALUE_OPERATORS = setOf("==", "!=", "<", "<=", ">", ">=", "contains", "!contains", "startsWith", "endsWith", "matches")
    }
}
