package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.port.out.EnvironmentScope

private fun EnvironmentScope.label(): String = if (this == EnvironmentScope.SHARED) "shared" else "private"

private fun List<EnvironmentScope>.labels(): String = joinToString(" and ") { it.label() }

/** The line the editor shows after a save. Never contains a variable value. */
fun saveMessage(outcome: SaveOutcome): String = when (outcome) {
    SaveOutcome.NothingToSave -> "Nothing to save."
    is SaveOutcome.Invalid -> "Cannot save: ${outcome.problems.size} problem${if (outcome.problems.size == 1) "" else "s"} below."
    is SaveOutcome.Changed ->
        if (outcome.written.isEmpty()) "The ${outcome.scope.label()} file changed on disk, so nothing was saved. It was loaded again; make your changes again."
        else "The ${outcome.scope.label()} file changed on disk after the ${outcome.written.labels()} file was saved. Both were loaded again; check them."
    is SaveOutcome.Failed ->
        if (outcome.written.isEmpty()) outcome.message
        else "${outcome.message} The ${outcome.written.labels()} file was already saved; the files were loaded again, check them."
    is SaveOutcome.Saved -> "Saved the ${outcome.written.labels()} file${if (outcome.written.size == 1) "" else "s"}."
}

/** Whether the editor must be loaded again after [outcome]: the draft was dropped or no longer matches the files. */
fun needsReload(outcome: SaveOutcome): Boolean = when (outcome) {
    is SaveOutcome.Changed -> true
    is SaveOutcome.Failed -> outcome.written.isNotEmpty()
    else -> false
}

/** One line per problem of [draft], naming the environment and variable it is about (by name, or by position when the name is empty). */
fun problemLines(draft: EnvironmentDraft, problems: List<DraftProblem>): List<String> = problems.map { p ->
    val env = p.environment?.let { draft.environments.getOrNull(it) }
    val variable = p.variable?.let { v -> env?.variables?.getOrNull(v) }
    val where = when {
        env == null -> ""
        variable == null -> "${env.name.ifEmpty { "(new environment)" }}: "
        else -> "${env.name.ifEmpty { "(new environment)" }} / ${variable.name.ifEmpty { "variable ${p.variable!! + 1}" }}: "
    }
    where + p.message
}
