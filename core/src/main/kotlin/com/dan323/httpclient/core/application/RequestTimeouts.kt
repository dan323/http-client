package com.dan323.httpclient.core.application

/**
 * Durations for the whole-exchange timeout: a whole, positive number with an optional `ms`, `s` or `m` suffix (any
 * case, plain numbers are seconds). Used for `# @timeout`, the CLI `--timeout` and the configured default.
 */
object RequestTimeouts {
    /** The total timeout of a request without `# @timeout`, unless the front end configures another one. */
    const val DEFAULT_MILLIS = 60_000L

    private val pattern = Regex("""(\d+)\s*(ms|s|m)?""", RegexOption.IGNORE_CASE)

    /**
     * Milliseconds for [raw]. [written] is what the error quotes (so `{{secret}}` never leaks its value) and [label]
     * names the setting in the message (`@timeout`, `--timeout`). Throws [IllegalArgumentException].
     */
    fun parseMillis(raw: String, written: String = raw, label: String = "@timeout"): Long {
        val m = pattern.matchEntire(raw.trim()) ?: throw IllegalArgumentException("Invalid $label value: '$written'")
        val n = m.groupValues[1].toLongOrNull() ?: throw IllegalArgumentException("Invalid $label value: '$written' (too large)")
        val factor = when (m.groupValues[2].lowercase()) {
            "ms" -> 1L
            "m" -> 60_000L
            else -> 1000L
        }
        val millis = try {
            Math.multiplyExact(n, factor)
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("Invalid $label value: '$written' (too large)")
        }
        require(millis > 0) { "Invalid $label value: '$written' (must be greater than zero)" }
        return millis
    }
}
