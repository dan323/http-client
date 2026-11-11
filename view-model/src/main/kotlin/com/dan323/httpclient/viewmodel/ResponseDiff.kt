package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.StoredResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** How a header differs between response A and response B (names compare case-insensitively). */
sealed interface HeaderChange {
    val name: String
    data class Added(override val name: String, val value: String) : HeaderChange
    data class Removed(override val name: String, val value: String) : HeaderChange
    data class Changed(override val name: String, val before: String, val after: String) : HeaderChange
}

enum class DiffKind(val prefix: String) { SAME("  "), REMOVED("- "), ADDED("+ ") }

/** One line of a body diff; [label] carries a `+` / `-` prefix so the change does not depend on colour alone. */
data class DiffLine(val kind: DiffKind, val text: String) {
    val label: String get() = kind.prefix + text
}

sealed interface BodyDiff {
    /** The bodies are the same (after pretty-printing JSON). */
    data object Same : BodyDiff

    /** Only the sizes are shown: [reason] says why the lines were not compared. */
    data class Skipped(val reason: String, val sizeA: Int, val sizeB: Int) : BodyDiff

    data class Lines(val lines: List<DiffLine>) : BodyDiff
}

/** A comparison of two responses; A is the earlier or reference one. */
data class ResponseDiff(
    val statusA: Int,
    val statusB: Int,
    val durationA: Long,
    val durationB: Long,
    val headers: List<HeaderChange>,
    val body: BodyDiff,
) {
    val statusChanged: Boolean get() = statusA != statusB

    /** `A: 200  →  B: 404`, or `Status 200 in both` when they are equal. */
    val statusLabel: String get() = if (statusChanged) "A: $statusA  →  B: $statusB" else "Status $statusA in both"

    /** `A: 35 ms  ·  B: 41 ms`. */
    val durationsLabel: String get() = "A: ${durationLabel(durationA)}  ·  B: ${durationLabel(durationB)}"

    val identical: Boolean get() = !statusChanged && headers.isEmpty() && body == BodyDiff.Same
}

/** Limits that keep a comparison cheap; above them the diff shows only sizes. */
object ResponseDiffLimits {
    /** Bodies larger than this (bytes) are not compared line by line. */
    const val MAX_BODY_BYTES = 1_000_000

    /** Bodies with more lines than this are not compared line by line. */
    const val MAX_LINES = 20_000

    /** After the common start and end are removed, at most this many line pairs are compared (the diff is quadratic). */
    const val MAX_CELLS = 4_000_000L
}

/**
 * Compares [a] with [b]: status, duration, headers and body. JSON bodies are pretty-printed with sorted keys first so key order
 * and spacing do not hide the real change; other text is compared as is; a body that is not valid UTF-8 shows only sizes.
 */
fun diffResponses(a: HttpResponse, b: HttpResponse): ResponseDiff = diffResponses(a, b, false, false, a.body.size.toLong(), b.body.size.toLong())

/**
 * Compares two stored responses. A stored body may be cut short, so equal retained bodies do not prove equal
 * responses: that case reports the bodies as not compared, with the original sizes.
 */
fun diffStoredResponses(a: StoredResponse, b: StoredResponse): ResponseDiff =
    diffResponses(a.response, b.response, a.bodyTruncated, b.bodyTruncated, a.originalBodySize, b.originalBodySize)

private fun diffResponses(a: HttpResponse, b: HttpResponse, cutA: Boolean, cutB: Boolean, sizeA: Long, sizeB: Long): ResponseDiff {
    val body = diffBodies(a.body, b.body)
    val partial = (cutA || cutB) && body == BodyDiff.Same
    val shown = if (partial) BodyDiff.Skipped("a stored body was cut short, so the complete bodies were not compared", sizeA.toIntSize(), sizeB.toIntSize()) else body
    return ResponseDiff(a.statusCode, b.statusCode, a.durationMillis, b.durationMillis, diffHeaders(a, b), shown)
}

private fun Long.toIntSize(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

private val canonicalPrinter = Json { prettyPrint = true }

/** [text] pretty-printed with object keys sorted (arrays keep their order) when it is JSON, else unchanged. */
private fun canonicalOrRaw(text: String): String {
    val element = parseJsonContainer(text.trim()) ?: return text
    return runCatching { canonicalPrinter.encodeToString(JsonElement.serializer(), sorted(element)) }.getOrDefault(text)
}

private fun sorted(e: JsonElement): JsonElement = when (e) {
    is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { it.key to sorted(it.value) })
    is JsonArray -> JsonArray(e.map(::sorted))
    else -> e
}

private fun joined(r: HttpResponse): Map<String, Pair<String, String>> {
    val out = linkedMapOf<String, Pair<String, String>>() // lower-case name -> (name as first seen, values joined)
    for (h in r.headers) {
        val key = h.name.lowercase()
        val previous = out[key]
        out[key] = if (previous == null) h.name to h.value else previous.first to previous.second + ", " + h.value
    }
    return out
}

private fun diffHeaders(a: HttpResponse, b: HttpResponse): List<HeaderChange> {
    val left = joined(a)
    val right = joined(b)
    val changes = mutableListOf<HeaderChange>()
    for ((key, l) in left) {
        val r = right[key]
        when {
            r == null -> changes += HeaderChange.Removed(l.first, l.second)
            r.second != l.second -> changes += HeaderChange.Changed(l.first, l.second, r.second)
        }
    }
    for ((key, r) in right) if (key !in left) changes += HeaderChange.Added(r.first, r.second)
    return changes.sortedBy { it.name.lowercase() }
}

private fun utf8OrNull(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: java.nio.charset.CharacterCodingException) { null }

private fun diffBodies(a: ByteArray, b: ByteArray): BodyDiff {
    if (a.contentEquals(b)) return BodyDiff.Same
    fun skipped(why: String) = BodyDiff.Skipped(why, a.size, b.size)
    if (a.size > ResponseDiffLimits.MAX_BODY_BYTES || b.size > ResponseDiffLimits.MAX_BODY_BYTES) return skipped("a body is larger than 1 MB")
    val textA = utf8OrNull(a) ?: return skipped("a body is not text")
    val textB = utf8OrNull(b) ?: return skipped("a body is not text")
    val left = canonicalOrRaw(textA).lines()
    val right = canonicalOrRaw(textB).lines()
    if (left == right) return BodyDiff.Same // equal once JSON is pretty-printed
    if (left.size > ResponseDiffLimits.MAX_LINES || right.size > ResponseDiffLimits.MAX_LINES) return skipped("a body has more than 20000 lines")
    return diffLines(left, right)?.let { BodyDiff.Lines(it) } ?: skipped("too many lines differ to compare")
}

/** Line diff by longest common subsequence; null when the differing middle is too large to compare. */
internal fun diffLines(a: List<String>, b: List<String>): List<DiffLine>? {
    var start = 0
    while (start < a.size && start < b.size && a[start] == b[start]) start++
    var endA = a.size
    var endB = b.size
    while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }
    val n = endA - start
    val m = endB - start
    if (n.toLong() * m > ResponseDiffLimits.MAX_CELLS) return null
    // lcs[i][j]: length of the longest common subsequence of a[start+i..endA) and b[start+j..endB)
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
        lcs[i][j] = if (a[start + i] == b[start + j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    }
    val out = ArrayList<DiffLine>(a.size + b.size)
    for (k in 0 until start) out += DiffLine(DiffKind.SAME, a[k])
    var i = 0
    var j = 0
    while (i < n || j < m) {
        when {
            i < n && j < m && a[start + i] == b[start + j] -> { out += DiffLine(DiffKind.SAME, a[start + i]); i++; j++ }
            // a removal is listed before the addition that replaces it
            i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1]) -> { out += DiffLine(DiffKind.REMOVED, a[start + i]); i++ }
            else -> { out += DiffLine(DiffKind.ADDED, b[start + j]); j++ }
        }
    }
    for (k in endA until a.size) out += DiffLine(DiffKind.SAME, a[k])
    return out
}
