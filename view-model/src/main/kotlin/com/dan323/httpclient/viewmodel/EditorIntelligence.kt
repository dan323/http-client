package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.application.OccurrenceKind
import com.dan323.httpclient.core.application.SourceRange
import com.dan323.httpclient.core.application.VariableAnalysis

/**
 * Analyses [text] for the editor under [choice]: only the variable names (never values) of the chosen environment are
 * known, as at run time. With no choice, or a name that is not in [environments], every non-file variable is undefined.
 */
fun analyzeFor(text: String, choice: EnvironmentChoice, environments: Map<String, Set<String>>): VariableAnalysis =
    VariableAnalysis.analyze(text, (choice as? EnvironmentChoice.Named)?.let { environments[it.name] }.orEmpty())

/**
 * Where the name at [offset] is defined: the `@x = ...` line of a variable, or the `# @name x` of a request that a
 * `{{x.response...}}` refers to; when the name is defined more than once, the definition in force at the use. Null when the caret is not on a name, or the definition is not in the file (an
 * environment variable, a dynamic variable, an undefined name). A definition is never its own target.
 */
fun declarationOf(analysis: VariableAnalysis, offset: Int): SourceRange? {
    val at = analysis.occurrenceAt(offset) ?: return null
    val wanted = when (at.kind) {
        OccurrenceKind.VARIABLE_USE -> OccurrenceKind.FILE_VARIABLE_DEFINITION
        OccurrenceKind.REQUEST_REFERENCE -> OccurrenceKind.REQUEST_DECLARATION
        else -> return null
    }
    // A name can be defined again further down: the one in force is the last before the use, and for a use that
    // comes first (a variable defined later, reported as a problem) the first definition there is.
    val definitions = analysis.findUsages(at.name).filter { it.kind == wanted }
    return (definitions.lastOrNull { it.range.offset < at.range.offset } ?: definitions.firstOrNull())?.range
}

/** Replace `start until end` of the old text by [replacement] to get the new text. */
data class TextEdit(val start: Int, val end: Int, val replacement: String)

/** The smallest single edit that turns [old] into [new] (so an editor keeps its markers and undo steps outside it). */
fun editBetween(old: String, new: String): TextEdit {
    var head = 0
    while (head < old.length && head < new.length && old[head] == new[head]) head++
    var tail = 0
    while (tail < old.length - head && tail < new.length - head && old[old.length - 1 - tail] == new[new.length - 1 - tail]) tail++
    return TextEdit(head, old.length - tail, new.substring(head, new.length - tail))
}
