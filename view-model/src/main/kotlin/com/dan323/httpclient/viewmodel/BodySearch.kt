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

