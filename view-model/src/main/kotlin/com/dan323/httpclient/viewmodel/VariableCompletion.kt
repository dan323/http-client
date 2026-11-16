package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.KnownVariable
import com.dan323.httpclient.core.application.VariableKind

/**
 * An unfinished `{{` reference at the caret: [start] is where the typed name begins (after `{{` and any spaces) and
 * [prefix] is what is typed so far, up to the caret. Completion replaces `start until caret`.
 */
data class CompletionContext(val start: Int, val prefix: String)

/**
 * The reference being typed at [caret], or null when the caret is not inside an unclosed `{{ ... }}` of its line.
 * Only the line is looked at (a reference never spans lines) and a `}}` before the caret closes the reference.
 */
fun completionContext(text: String, caret: Int): CompletionContext? {
    if (caret < 0 || caret > text.length) return null
    val lineStart = text.lastIndexOf('\n', caret - 1).let { if (it < 0) 0 else it + 1 }
    val open = text.lastIndexOf("{{", caret - 1).takeIf { it >= lineStart && it + 2 <= caret } ?: return null
    if (text.indexOf("}}", open + 2).let { it in 0..caret - 2 }) return null
    var start = open + 2
    while (start < caret && (text[start] == ' ' || text[start] == '\t')) start++
    val typed = text.substring(start, caret)
    // A space ends the name, except in the dynamic variables that take arguments (`$timestamp 1 d`, `$processEnv NAME`).
    if (typed.any { it == ' ' || it == '\t' } && !typed.startsWith("$")) return null
    return CompletionContext(start, typed)
}

/** One suggestion: what is [label]led in the list and the [name] it inserts. */
data class CompletionItem(val label: String, val name: String, val kind: VariableKind)

/** At most this many suggestions are offered. */
const val MAX_SUGGESTIONS = 12

/**
 * The names of [known] that match [prefix]: those that start with it first (case ignored), then those that merely
 * contain it. Nothing is offered for a prefix that is already an exact, complete name.
 */
fun completions(known: List<KnownVariable>, prefix: String): List<CompletionItem> {
    val p = prefix.lowercase()
    val starts = known.filter { it.name.lowercase().startsWith(p) }
    val contains = if (p.isEmpty()) emptyList() else known.filter { !it.name.lowercase().startsWith(p) && p in it.name.lowercase() }
    return (starts + contains).filter { it.name != prefix }.take(MAX_SUGGESTIONS).map { CompletionItem(it.name, it.name, it.kind) }
}

/** The text after accepting [item] for [context] with the caret at [caret], and where the caret goes. */
data class Completed(val text: String, val caret: Int)

/**
 * Replaces what is typed with the name. Variables and dynamic variables close the reference with `}}` (unless it is
 * already closed right after the caret); a request prefix such as `login.response.` stays open for the part that follows.
 */
fun applyCompletion(text: String, context: CompletionContext, caret: Int, item: CompletionItem): Completed {
    val before = text.substring(0, context.start)
    val after = text.substring(caret)
    val closes = item.kind != VariableKind.REQUEST_RESPONSE
    val insert = item.name + if (closes && !after.trimStart(' ', '\t').startsWith("}}")) "}}" else ""
    return Completed(before + insert + after, context.start + insert.length)
}

/** The short word shown beside a suggestion. */
fun kindLabel(kind: VariableKind): String = when (kind) {
    VariableKind.FILE -> "file variable"
    VariableKind.ENVIRONMENT -> "environment"
    VariableKind.REQUEST_RESPONSE -> "response"
    VariableKind.DYNAMIC -> "dynamic"
}
