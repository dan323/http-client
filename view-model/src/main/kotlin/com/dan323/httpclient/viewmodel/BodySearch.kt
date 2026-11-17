package com.dan323.httpclient.viewmodel

/** Most matches [findMatches] returns; more than this are not highlighted one by one. */
const val MAX_SEARCH_MATCHES = 5_000

/**
 * The non-overlapping matches of [query] in [text], in order, as index ranges into [text]. The search ignores case
 * unless [matchCase]; an empty query has no matches. At most [MAX_SEARCH_MATCHES] are returned.
 */
fun findMatches(text: String, query: String, matchCase: Boolean = false): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<IntRange>()
    var from = 0
    while (out.size < MAX_SEARCH_MATCHES) {
        val at = text.indexOf(query, from, ignoreCase = !matchCase)
        if (at < 0) break
        out += at until at + query.length
        from = at + query.length
    }
    return out
}

/** The match to go to from [current] ([forward] or back), wrapping around; 0 when there are no matches. */
fun stepMatch(current: Int, count: Int, forward: Boolean): Int = when {
    count <= 0 -> 0
    forward -> (current + 1) % count
    else -> (current - 1 + count) % count
}

/** `2 of 5`, `No matches`, or an empty text when nothing is searched for; `5000+` when the limit was reached. */
fun matchLabel(query: String, current: Int, count: Int): String = when {
    query.isEmpty() -> ""
    count == 0 -> "No matches"
    count >= MAX_SEARCH_MATCHES -> "${current + 1} of $MAX_SEARCH_MATCHES+"
    else -> "${current + 1} of $count"
}


/** [text] after one replacement, and [caret]: the end of the inserted text. */
data class Replacement(val text: String, val caret: Int)

/** Replaces the characters of [match] in [text] with [replacement]. */
fun replaceMatch(text: String, match: IntRange, replacement: String): Replacement {
    // A range that no longer fits the text (it changed since the match was found) changes nothing.
    if (match.first < 0 || match.first > text.length || match.last < match.first) return Replacement(text, match.first.coerceIn(0, text.length))
    val end = (match.last + 1).coerceAtMost(text.length)
    return Replacement(text.substring(0, match.first) + replacement + text.substring(end), match.first + replacement.length)
}

/** The index in [matches] of the first match that starts at or after [from], wrapping to 0 when there is none (and 0 when there are no matches). */
fun nextMatchIndex(matches: List<IntRange>, from: Int): Int = matches.indexOfFirst { it.first >= from }.coerceAtLeast(0)

/**
 * The match to go to after a replacement that replaced the text at [replacedStart] and ended at [caret] (the end of the
 * inserted text), in the [matches] of the new text: the first one at or after the caret, else, wrapping, the first one
 * that ends before the replaced text; null when there is none. A match that overlaps the inserted text is never
 * offered, so replacing `a` by `aa` moves on (or stops) instead of selecting its own output again, and replacing
 * `ab` by `b` in `aab` does not select the `ab` that now ends with the inserted `b`.
 */
fun matchAfterReplacement(matches: List<IntRange>, replacedStart: Int, caret: Int): Int? =
    matches.indexOfFirst { it.first >= caret }.takeIf { it >= 0 } ?: matches.indexOfFirst { it.last < replacedStart }.takeIf { it >= 0 }

/** [text] after every match was replaced, and how many there were. */
data class ReplacedAll(val text: String, val count: Int)

/** Replaces every non-overlapping match of [query] (case as in [findMatches]); there is no limit on the count here. */
fun replaceAll(text: String, query: String, replacement: String, matchCase: Boolean = false): ReplacedAll {
    if (query.isEmpty()) return ReplacedAll(text, 0)
    val out = StringBuilder()
    var from = 0
    var count = 0
    while (true) {
        val at = text.indexOf(query, from, ignoreCase = !matchCase)
        if (at < 0) break
        out.append(text, from, at).append(replacement)
        from = at + query.length
        count++
    }
    out.append(text, from, text.length)
    return ReplacedAll(out.toString(), count)
}

/** `Replaced 3 matches`, `Replaced 1 match`, or `No matches` for the status line after Replace all. */
fun replacedLabel(count: Int): String = when (count) {
    0 -> "No matches"
    1 -> "Replaced 1 match"
    else -> "Replaced $count matches"
}

/** The index of the last match that starts before [before], wrapping to the last match when there is none (0 when there are no matches). */
fun previousMatchIndex(matches: List<IntRange>, before: Int): Int =
    matches.indexOfLast { it.first < before }.let { if (it >= 0) it else (matches.size - 1).coerceAtLeast(0) }

/**
 * The find bar's count: `No matches`, `3 matches` while no match is selected ([current] null), or `2 of 3` for the
 * selected one; empty for an empty query. At the limit of [MAX_SEARCH_MATCHES] it reads `5000+`.
 */
fun findLabel(query: String, current: Int?, count: Int): String = when {
    query.isEmpty() -> ""
    count == 0 -> "No matches"
    current != null -> matchLabel(query, current, count)
    count >= MAX_SEARCH_MATCHES -> "$MAX_SEARCH_MATCHES+ matches"
    count == 1 -> "1 match"
    else -> "$count matches"
}
