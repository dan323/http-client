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

/**
 * One suggestion: the [name] as listed. For the dynamic variables with placeholders it is a template such as
 * `$random.integer(from, to)` or `$env.NAME`; [applyCompletion] inserts only what is real (see there).
 */
data class CompletionItem(val name: String, val kind: VariableKind)

/** At most this many suggestions are offered. */
const val MAX_SUGGESTIONS = 12

/**
 * The names of [known] that match [prefix]: those that start with it first (case ignored), then those that merely
 * contain it. A name equal to [prefix] is not offered (it is already complete); names are case-sensitive at run time,
 * so a name that differs only in case still is, as the correction.
 */
fun completions(known: List<KnownVariable>, prefix: String): List<CompletionItem> {
    val p = prefix.lowercase()
    val starts = known.filter { it.name.lowercase().startsWith(p) }
    val contains = if (p.isEmpty()) emptyList() else known.filter { !it.name.lowercase().startsWith(p) && p in it.name.lowercase() }
    return (starts + contains).filter { it.name != prefix }.take(MAX_SUGGESTIONS).map { CompletionItem(it.name, it.kind) }
}

/** The text after accepting [item] for [context] with the caret at [caret], and where the caret goes. */
data class Completed(val text: String, val caret: Int)

private const val NAME_END = " \t\r\n{}"

/**
 * Replaces the name being typed, including the rest of it after the caret (`{{ba|se}}` becomes `{{basket}}`, not
 * `{{basket}}se}}`). Variables and dynamic variables close the reference with `}}` (unless it is already closed right
 * after the name); a request prefix such as `login.response.` stays open for the part that follows. Templates with
 * placeholders insert only what is real: `$random.integer(from, to)` gives `$random.integer()` with the caret between
 * the parentheses, and `$env.NAME` / `$processEnv NAME` stay open after the `.` / the space for the name to be typed.
 */
fun applyCompletion(text: String, context: CompletionContext, caret: Int, item: CompletionItem): Completed {
    var tailEnd = caret
    while (tailEnd < text.length && text[tailEnd] !in NAME_END) tailEnd++
    val before = text.substring(0, context.start)
    val after = text.substring(tailEnd)
    val name = item.name
    val open = name.indexOf('(')
    val (body, caretInBody, closes) = when {
        item.kind == VariableKind.REQUEST_RESPONSE -> Triple(name, name.length, false)
        open >= 0 -> Triple(name.substring(0, open) + "()", open + 1, true)
        name.endsWith("NAME") -> name.removeSuffix("NAME").let { Triple(it, it.length, false) }
        else -> Triple(name, name.length, true)
    }
    val closing = if (closes && !after.trimStart(' ', '\t').startsWith("}}")) "}}" else ""
    // After the closing braces, except for a call whose arguments are still to be typed.
    val caretAt = if (open >= 0) caretInBody else body.length + closing.length
    return Completed(before + body + closing + after, context.start + caretAt)
}

/** The short word shown beside a suggestion. */
fun kindLabel(kind: VariableKind): String = when (kind) {
    VariableKind.FILE -> "file variable"
    VariableKind.ENVIRONMENT -> "environment"
    VariableKind.REQUEST_RESPONSE -> "response"
    VariableKind.DYNAMIC -> "dynamic"
}
