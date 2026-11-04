package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.domain.HttpResponse
import java.math.BigDecimal

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
 *
 * - `<expression>` is `status`, `headers.Name` or `body.<anything ResponseExtractor understands>`; any other
 *   prefix is an invalid assertion (so a typo cannot pass silently). It ends at the first whitespace; to use an
 *   expression with spaces wrap it in single or double quotes: `"body.$.items[?(@.id == 1)]" exists`.
 * - `expected` is the rest of the line, trimmed, inner spaces kept. Wrapped in matching quotes it is taken
 *   literally (quotes removed, so `== ""` asserts an empty value and `== " a "` keeps the spaces) and `==`/`!=`
 *   then compare as exact text only. An unquoted empty expected value is invalid.
 *
 * Operators: `==`, `!=`, `<`, `<=`, `>`, `>=`, `contains`, `!contains`, `startsWith`, `endsWith`,
 * `matches`, `exists`, `!exists`.
 *
 * - `==`/`!=`: equal when the texts are identical; otherwise, for an unquoted expected, equal when both sides are
 *   strict decimal numbers (`-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d{1,4})?`) with the same [BigDecimal] value, so
 *   `5 == 5.0` and `1e3 == 1000` hold, big integers are exact, and `007`, `NaN`, `Infinity` are plain text
 *   (`007 == 7` fails). Quote the expected value to force text comparison.
 * - `<`, `<=`, `>`, `>=` need strict numbers on both sides.
 * - `matches`: the whole value matches the regex, `.` also matches newlines; a pattern running over 2 seconds
 *   is aborted and reported as invalid.
 * - A value that is missing (nothing found) fails every operator except `!exists`; use `!exists` to assert absence.
 *   An XPath that evaluates to a string (`xpath:string(//a)`, `count(...)`) yields `""` rather than missing,
 *   so `exists` holds for it; use `== ""` to test for emptiness.
 *
 * Failure messages show at most [MAX_SHOWN] characters of the actual value.
 */
class AssertionEvaluator(private val extractor: ResponseExtractor) {

    fun evaluate(assertions: List<String>, response: HttpResponse): List<AssertionResult> =
        assertions.map { evaluate(it, response) }

    fun evaluate(assertion: String, response: HttpResponse): AssertionResult {
        val parsed = parse(assertion) ?: return invalid(assertion, "missing expression")
        val (expr, rest) = parsed
        if (expr == null) return invalid(assertion, "unterminated quote in expression")
        val opAndExpected = rest.trim().split(Regex("\\s+"), limit = 2)
        val op = opAndExpected[0].ifEmpty { return invalid(assertion, "missing operator") }
        val rawExpected = opAndExpected.getOrNull(1)?.trim()
        if (!(expr == "status" || expr.startsWith("headers.") || expr.startsWith("body."))) {
            return invalid(assertion, "unknown expression '$expr' (use status, headers.<Name> or body.<...>; quote expressions that contain spaces)")
        }
        if (op != "exists" && op != "!exists" && op !in VALUE_OPERATORS) {
            return invalid(assertion, "unknown operator '$op' (expressions containing spaces must be quoted)")
        }
        val quoted = rawExpected != null && rawExpected.length >= 2 &&
            rawExpected[0] in "\"'" && rawExpected.last() == rawExpected[0]
        val expected = if (quoted) rawExpected!!.substring(1, rawExpected.length - 1) else rawExpected
        val actual = extractor.extract(response, expr)

        fun result(passed: Boolean, exp: String?) = AssertionResult(
            assertion, passed, exp, actual,
            if (passed) "OK" else "expected $expr ${describe(op)}${exp?.let { " ${shorten(it)}" } ?: ""}, but was ${actual?.let(::shorten) ?: "missing"}",
        )

        when (op) {
            "exists" -> return result(actual != null, null)
            "!exists" -> return result(actual == null, null)
        }
        if (expected == null || (expected.isEmpty() && !quoted)) {
            return invalid(assertion, "operator '$op' needs an expected value (use \"\" for an empty string)")
        }
        if (actual == null) return result(false, expected)

        val a = number(actual)
        val e = number(expected)
        val passed = when (op) {
            "==" -> equal(actual, expected, quoted, a, e)
            "!=" -> !equal(actual, expected, quoted, a, e)
            "<", "<=", ">", ">=" -> {
                if (a == null || e == null) {
                    return invalid(assertion, "'$op' needs numbers (actual: ${shorten(actual)}, expected: ${shorten(expected)})", actual)
                }
                val c = a.compareTo(e)
                when (op) { "<" -> c < 0; "<=" -> c <= 0; ">" -> c > 0; else -> c >= 0 }
            }
            "contains" -> actual.contains(expected)
            "!contains" -> !actual.contains(expected)
            "startsWith" -> actual.startsWith(expected)
            "endsWith" -> actual.endsWith(expected)
            else -> try {
                Regex(expected, RegexOption.DOT_MATCHES_ALL).matches(RegexGuard(actual))
            } catch (ex: RegexTimeoutException) {
                return invalid(assertion, ex.message ?: "regex timeout", actual)
            } catch (ex: Exception) {
                return invalid(assertion, "bad regex: ${ex.message?.lineSequence()?.firstOrNull()}", actual)
            }
        }
        return result(passed, expected)
    }

    private fun equal(actual: String, expected: String, quoted: Boolean, a: BigDecimal?, e: BigDecimal?) =
        actual == expected || (!quoted && a != null && e != null && a.compareTo(e) == 0)

    /** Splits off the expression; null when the line is blank, (null, _) when a quote is not closed. */
    private fun parse(assertion: String): Pair<String?, String>? {
        val s = assertion.trim()
        if (s.isEmpty()) return null
        if (s[0] == '"' || s[0] == '\'') {
            val end = s.indexOf(s[0], 1)
            return if (end < 0) null to "" else s.substring(1, end) to s.substring(end + 1)
        }
        val ws = s.indexOfFirst { it.isWhitespace() }
        return if (ws < 0) s to "" else s.substring(0, ws) to s.substring(ws)
    }

    private fun number(s: String): BigDecimal? = if (NUMBER.matches(s)) BigDecimal(s) else null

    private fun shorten(s: String) = if (s.length <= MAX_SHOWN) s else s.take(MAX_SHOWN) + "... (${s.length} chars)"

    private fun describe(op: String) = when (op) {
        "exists" -> "to exist"
        "!exists" -> "not to exist"
        "==" -> "to equal"
        "!=" -> "not to equal"
        else -> op
    }

    private fun invalid(assertion: String, why: String, actual: String? = null) =
        AssertionResult(assertion, false, null, actual?.let(::shorten), "invalid assertion: $why")

    companion object {
        const val MAX_SHOWN = 200
        private val NUMBER = Regex("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d{1,4})?")
        private val VALUE_OPERATORS = setOf("==", "!=", "<", "<=", ">", ">=", "contains", "!contains", "startsWith", "endsWith", "matches")
    }
}
