package com.dan323.httpclient.core.application

import com.dan323.httpclient.core.port.out.EnvironmentVariables
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.math.BigDecimal
import java.security.SecureRandom
import java.util.UUID
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Evaluates `{{$...}}` dynamic variables. Randomness, clock and OS environment are injected so
 * results can be made deterministic in tests. Each call evaluates afresh.
 *
 * Supported (the text between `{{` and `}}`, without the braces):
 * - `$uuid`, `$random.uuid`
 * - `$timestamp [offset]` (Unix seconds), `$isoTimestamp [offset]` (ISO-8601 UTC); offset is `<n> <unit>`, e.g. `1 d`, `-2 h`
 *   with units `ms s m h d w M y` (`M` = calendar months, `y` = calendar years, the rest fixed durations)
 * - `$randomInt` (0..999) or `$randomInt <from> <to>` (both inclusive)
 * - `$random.integer(from, to)` (inclusive), `$random.float(from, to)` (from inclusive, to exclusive),
 *   `$random.alphabetic(n)`, `$random.alphanumeric(n)`, `$random.hexadecimal(n)`, `$random.email`
 * - `$env.NAME` (alias `$processEnv NAME`), only for names the [env] port allows
 *
 * [random] defaults to [SecureRandom] so `$uuid` and `$random.*` are unpredictable; tests inject a seeded one.
 */
class DynamicVariables(
    private val random: Random = SecureRandom().asKotlinRandom(),
    private val clock: Clock = Clock.systemUTC(),
    private val env: EnvironmentVariables = EnvironmentVariables.NONE,
) {
    /** Value of the dynamic variable [name], or null if it is not a known dynamic variable. */
    fun evaluate(name: String): String? {
        if (!name.startsWith("$")) return null
        val head = name.takeWhile { !it.isWhitespace() && it != '(' }
        val rest = name.substring(head.length).trim()
        return when (head) {
            "\$uuid", "\$random.uuid" -> noArgs(name, rest) { uuid() }
            "\$timestamp" -> now(name, rest).toEpochSecond().toString()
            "\$isoTimestamp" -> now(name, rest).toInstant().toString()
            "\$randomInt" -> randomInt(name, rest)
            "\$random.email" -> noArgs(name, rest.removeSuffix("()").trim()) { email() }
            "\$random.integer" -> {
                val (a, b) = args(name, rest, 2)
                between(name, long(name, a), long(name, b))
            }
            "\$random.float" -> {
                val (a, b) = args(name, rest, 2)
                val from = double(name, a)
                val to = double(name, b)
                if (from > to) invalid(name, "from must not exceed to")
                plain(if (from == to) from else random.nextDouble(from, to))
            }
            "\$random.alphabetic" -> chars(name, rest, ALPHA)
            "\$random.alphanumeric" -> chars(name, rest, ALPHA + DIGITS)
            "\$random.hexadecimal" -> chars(name, rest, DIGITS + "abcdef")
            else -> when {
                head == "\$processEnv" -> {
                    if (rest.isEmpty() || rest.any { it.isWhitespace() }) invalid(name, "expected \$processEnv NAME")
                    envValue(name, rest)
                }
                head.startsWith("\$env.") -> noArgs(name, rest) {
                    val key = head.removePrefix("\$env.")
                    if (key.isEmpty()) invalid(name, "expected \$env.NAME")
                    envValue(name, key)
                }
                else -> null
            }
        }
    }

    private fun envValue(name: String, key: String): String {
        if (!env.isAllowed(key)) invalid(name, "environment variable '$key' is not allowed; ${env.allowHint}")
        return env.get(key) ?: throw UndefinedVariableException("\$env.$key")
    }

    /** Plain decimal text (never scientific notation) that keeps at least one fractional digit. */
    private fun plain(value: Double): String {
        val s = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        return if ('.' in s) s else "$s.0"
    }

    private fun uuid(): String {
        val b = random.nextBytes(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        var msb = 0L
        var lsb = 0L
        for (i in 0..7) msb = (msb shl 8) or (b[i].toLong() and 0xff)
        for (i in 8..15) lsb = (lsb shl 8) or (b[i].toLong() and 0xff)
        return UUID(msb, lsb).toString()
    }

    private fun email(): String = "${pick(8, ALPHA.lowercase() + DIGITS)}@${pick(6, ALPHA.lowercase())}.com"

    private fun pick(n: Int, alphabet: String) = buildString { repeat(n) { append(alphabet[random.nextInt(alphabet.length)]) } }

    private fun chars(name: String, rest: String, alphabet: String): String {
        val text = args(name, rest, 1)[0]
        val n = text.toIntOrNull() ?: invalid(name, "length must be a whole number, got '$text'")
        if (n < 0 || n > MAX_LENGTH) invalid(name, "length must be between 0 and $MAX_LENGTH")
        return pick(n, alphabet)
    }

    private fun randomInt(name: String, rest: String): String {
        if (rest.isEmpty()) return random.nextInt(0, 1000).toString()
        val parts = rest.split(Regex("""\s+"""))
        if (parts.size != 2) invalid(name, "expected \$randomInt or \$randomInt <from> <to>")
        return between(name, long(name, parts[0]), long(name, parts[1]))
    }

    /** Uniform integer in `from..to` inclusive. */
    private fun between(name: String, from: Long, to: Long): String {
        if (from > to) invalid(name, "from must not exceed to")
        return when {
            from == to -> from
            to < Long.MAX_VALUE -> random.nextLong(from, to + 1)
            // `to + 1` would overflow: sample from-1 until MAX (= from-1..MAX-1) and shift up by one.
            from > Long.MIN_VALUE -> random.nextLong(from - 1, Long.MAX_VALUE) + 1
            else -> random.nextLong() // the whole Long range
        }.toString()
    }

    private inline fun noArgs(name: String, rest: String, f: () -> String): String {
        if (rest.isNotEmpty()) invalid(name, "takes no arguments")
        return f()
    }

    /** Parses `(a, b)` into exactly [count] trimmed arguments. */
    private fun args(name: String, rest: String, count: Int): List<String> {
        if (!rest.startsWith("(") || !rest.endsWith(")")) invalid(name, "expected $count argument(s) in parentheses")
        val list = rest.substring(1, rest.length - 1).split(",").map { it.trim() }
        if (list.size != count || list.any { it.isEmpty() }) invalid(name, "expected $count argument(s)")
        return list
    }

    private fun long(name: String, text: String): Long = text.toLongOrNull() ?: invalid(name, "'$text' is not a whole number")

    private fun double(name: String, text: String): Double =
        text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: invalid(name, "'$text' is not a number")

    private fun now(name: String, rest: String): ZonedDateTime {
        val now = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC))
        if (rest.isEmpty()) return now
        val m = OFFSET.matchEntire(rest) ?: invalid(name, "offset must look like '1 d' or '-2 h' (units: ms s m h d w M y)")
        val n = m.groupValues[1].toLongOrNull() ?: invalid(name, "offset amount is too large")
        return try {
            when (val unit = m.groupValues[2]) {
                "ms" -> now.plus(Duration.ofMillis(n))
                "s" -> now.plus(Duration.ofSeconds(n))
                "m" -> now.plus(Duration.ofMinutes(n))
                "h" -> now.plus(Duration.ofHours(n))
                "d" -> now.plusDays(n)
                "w" -> now.plusWeeks(n)
                "M" -> now.plusMonths(n)
                "y" -> now.plusYears(n)
                else -> invalid(name, "unknown offset unit '$unit' (units: ms s m h d w M y)")
            }
        } catch (e: java.time.DateTimeException) {
            invalid(name, "offset is out of range")
        } catch (e: ArithmeticException) {
            invalid(name, "offset is out of range")
        }
    }

    private fun invalid(name: String, why: String): Nothing = throw InvalidDynamicVariableException(name, why)

    companion object {
        private const val ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private const val DIGITS = "0123456789"
        private const val MAX_LENGTH = 10_000
        private val OFFSET = Regex("""([+-]?\d+)\s*([A-Za-z]+)""")
        val DEFAULT = DynamicVariables()
    }
}

class InvalidDynamicVariableException(val variable: String, reason: String) :
    IllegalArgumentException("Invalid dynamic variable {{$variable}}: $reason")
