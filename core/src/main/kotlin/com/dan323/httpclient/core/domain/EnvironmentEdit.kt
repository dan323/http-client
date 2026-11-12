package com.dan323.httpclient.core.domain

/** The editable view of an environment file: environment name to its variables, in file order. `$shared` is an entry like any other. */
typealias EditableEnvironments = Map<String, Map<String, String>>

/** What names may look like. */
object EnvironmentNames {
    /** The pseudo environment whose variables are added to every environment. */
    const val SHARED = "\$shared"

    /** Why [name] cannot name an environment, or null. */
    fun environmentProblem(name: String): String? = when {
        name.isBlank() -> "An environment name cannot be empty."
        name != name.trim() -> "An environment name cannot start or end with a space."
        else -> null
    }

    /** Why [name] cannot name a variable (the same rule as `--var`: no spaces or braces), or null. */
    fun variableProblem(name: String): String? = when {
        name.isEmpty() -> "A variable name cannot be empty."
        name.any { it.isWhitespace() || it == '{' || it == '}' } -> "A variable name cannot contain spaces or braces."
        else -> null
    }
}

/** An edit that cannot be applied (an unknown or duplicate name, an invalid name). The message names environments and variables, never values. */
class EnvironmentEditException(message: String) : RuntimeException(message)

/**
 * One change to an environment file. Edits are applied in order; unchanged entries are left exactly as they are
 * (the file adapter keeps their JSON values, such as numbers, as written).
 */
sealed interface EnvironmentEdit {
    /** Sets [name] in the existing environment [environment] (adds it or replaces its value). */
    data class SetVariable(val environment: String, val name: String, val value: String) : EnvironmentEdit

    /** Removes [name] from [environment]; nothing happens when it is not there. */
    data class RemoveVariable(val environment: String, val name: String) : EnvironmentEdit

    /** Adds an empty environment; it must not exist yet. */
    data class AddEnvironment(val name: String) : EnvironmentEdit

    /** Renames [from] to [to], keeping its place and variables; [to] must not exist, and `$shared` cannot be renamed or be a target. */
    data class RenameEnvironment(val from: String, val to: String) : EnvironmentEdit

    /** Removes [name] and its variables. */
    data class RemoveEnvironment(val name: String) : EnvironmentEdit
}

/** Why this edit's names are not allowed, or null. A variable that is already in a file is never checked, only what an edit writes. */
fun EnvironmentEdit.problem(): String? = when (this) {
    is EnvironmentEdit.SetVariable -> EnvironmentNames.environmentProblem(environment) ?: EnvironmentNames.variableProblem(name)
    is EnvironmentEdit.RemoveVariable -> null
    is EnvironmentEdit.AddEnvironment -> EnvironmentNames.environmentProblem(name)
    is EnvironmentEdit.RenameEnvironment -> when {
        from == EnvironmentNames.SHARED || to == EnvironmentNames.SHARED -> "The environment ${EnvironmentNames.SHARED} cannot be renamed."
        else -> EnvironmentNames.environmentProblem(to)
    }
    is EnvironmentEdit.RemoveEnvironment -> null
}

/** [this] after [edits], without touching the original. Throws [EnvironmentEditException] for an edit that cannot be applied. */
fun EditableEnvironments.applying(edits: List<EnvironmentEdit>): EditableEnvironments {
    val result = LinkedHashMap<String, Map<String, String>>(this)
    for (edit in edits) {
        edit.problem()?.let { throw EnvironmentEditException(it) }
        when (edit) {
            is EnvironmentEdit.SetVariable -> {
                val vars = result[edit.environment] ?: throw unknown(edit.environment)
                result[edit.environment] = LinkedHashMap(vars).also { it[edit.name] = edit.value }
            }
            is EnvironmentEdit.RemoveVariable -> result[edit.environment]?.let { vars ->
                result[edit.environment] = LinkedHashMap(vars).also { it.remove(edit.name) }
            } ?: throw unknown(edit.environment)
            is EnvironmentEdit.AddEnvironment -> {
                if (edit.name in result) throw EnvironmentEditException("The environment ${edit.name} already exists.")
                result[edit.name] = emptyMap()
            }
            is EnvironmentEdit.RenameEnvironment -> {
                if (edit.from !in result) throw unknown(edit.from)
                if (edit.to in result) throw EnvironmentEditException("The environment ${edit.to} already exists.")
                val renamed = LinkedHashMap<String, Map<String, String>>()
                for ((k, v) in result) renamed[if (k == edit.from) edit.to else k] = v
                result.clear(); result.putAll(renamed)
            }
            is EnvironmentEdit.RemoveEnvironment -> if (result.remove(edit.name) == null) throw unknown(edit.name)
        }
    }
    return result
}

private fun unknown(name: String) = EnvironmentEditException("Unknown environment: $name")
