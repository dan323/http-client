package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.domain.EnvironmentEdit
import com.dan323.httpclient.core.domain.EnvironmentEditException
import com.dan323.httpclient.core.domain.EnvironmentNames
import com.dan323.httpclient.core.domain.HiddenContent
import com.dan323.httpclient.core.domain.applying
import com.dan323.httpclient.core.port.out.EnvironmentFileContent
import com.dan323.httpclient.core.port.out.EnvironmentScope

/** What a private value shows instead of its text until the user asks to see it. */
const val MASKED_VALUE = "••••••••"

/** One row of the editor table. A private variable lives in the private file (secrets, never committed), the others in the shared one. */
data class EditorVariable(val name: String, val value: String, val private: Boolean = true) {
    /** The text to show: a private value is masked (an empty one is not) unless [reveal]. */
    fun shown(reveal: Boolean): String = if (private && !reveal && value.isNotEmpty()) MASKED_VALUE else value
}

/**
 * One environment in the editor. [original] is its name in the files (null for a new one), which is how a rename is told
 * from a removal plus an addition; [locked] means a file holds it as something that is not a JSON object, so its variables
 * cannot be edited (it can still be renamed or removed).
 */
data class EditorEnvironment(
    val name: String,
    val variables: List<EditorVariable>,
    val original: String? = null,
    val locked: Boolean = false,
)

/** Something wrong with the draft: [environment] and [variable] are indices into the draft (null when it is not about one), the [message] never contains a value. */
data class DraftProblem(val environment: Int?, val variable: Int?, val message: String)

/** What the files held when the editor loaded them: the contents of both and their versions. */
internal class Baseline(val shared: EnvironmentFileContent, val private: EnvironmentFileContent) {
    fun content(scope: EnvironmentScope): EnvironmentFileContent = if (scope == EnvironmentScope.SHARED) shared else private
}

/**
 * The editable state of the environment editor: a table of variables per environment, each with a Private flag, over what
 * the two files held when they were loaded. It is immutable; every change returns a new draft. A variable present in both
 * files is shown once, as private with the private value ([hasSharedCopy] tells), and a private variable keeps its shared
 * copy untouched. [edits] turns the difference to the files into the [EnvironmentEdit]s of each file, [check] says why it
 * cannot be saved.
 */
class EnvironmentDraft internal constructor(
    val environments: List<EditorEnvironment>,
    internal val baseline: Baseline,
) {
    private fun with(list: List<EditorEnvironment>) = EnvironmentDraft(list, baseline)

    private fun changeEnvironment(i: Int, change: (EditorEnvironment) -> EditorEnvironment): EnvironmentDraft {
        val env = environments.getOrNull(i) ?: return this
        val changed = change(env)
        return if (changed == env) this else with(environments.toMutableList().also { it[i] = changed })
    }

    /** A new, empty environment at the end. */
    fun addEnvironment(name: String = ""): EnvironmentDraft = with(environments + EditorEnvironment(name, emptyList()))

    fun renameEnvironment(i: Int, name: String): EnvironmentDraft = changeEnvironment(i) { it.copy(name = name) }

    fun removeEnvironment(i: Int): EnvironmentDraft =
        if (i in environments.indices) with(environments.filterIndexed { index, _ -> index != i }) else this

    /** A new empty variable at the end of environment [i], private so that a secret typed into it is not committed by accident; nothing happens in a locked environment. */
    fun addVariable(i: Int): EnvironmentDraft =
        changeEnvironment(i) { if (it.locked) it else it.copy(variables = it.variables + EditorVariable("", "")) }

    fun updateVariable(i: Int, j: Int, change: (EditorVariable) -> EditorVariable): EnvironmentDraft =
        changeEnvironment(i) { env ->
            val v = env.variables.getOrNull(j) ?: return@changeEnvironment env
            env.copy(variables = env.variables.toMutableList().also { it[j] = change(v) })
        }

    fun removeVariable(i: Int, j: Int): EnvironmentDraft =
        changeEnvironment(i) { env -> env.copy(variables = env.variables.filterIndexed { index, _ -> index != j }) }

    /** The version of the file of [scope] this draft was loaded from (null when it did not exist). */
    fun version(scope: EnvironmentScope): String? = baseline.content(scope).version

    /** Whether the private variable [name] of environment [i] also has a value in the shared file, which stays as it is while the variable is private. */
    fun hasSharedCopy(i: Int, name: String): Boolean {
        val env = environments.getOrNull(i) ?: return false
        return env.variables.any { it.name == name && it.private } && wasInBoth(env, name)
    }

    /** Whether [name] was in both files of [env] when they were loaded: only then is its shared copy left alone while it is private (a shared variable made private moves). */
    private fun wasInBoth(env: EditorEnvironment, name: String): Boolean {
        val original = env.original ?: return false
        return name in baseline.shared.environments[original].orEmpty() && name in baseline.private.environments[original].orEmpty()
    }

    /** The edits that bring the file of [scope] from what was loaded to this draft; empty when nothing changes there. */
    fun edits(scope: EnvironmentScope): List<EnvironmentEdit> {
        val file = baseline.content(scope).environments
        val kept = environments.mapNotNull { it.original }.toSet()
        val edits = mutableListOf<EnvironmentEdit>()
        for (name in file.keys) if (name !in kept) edits += EnvironmentEdit.RemoveEnvironment(name)
        for (env in environments) {
            val old = env.original?.takeIf { it in file }
            if (old != null && old != env.name) edits += EnvironmentEdit.RenameEnvironment(old, env.name)
        }
        for (env in environments) {
            val old = env.original?.let { file[it] }
            val desired = if (env.locked) old.orEmpty() else desired(env, scope)
            if (old == null && (desired.isNotEmpty() || (env.original == null && scope == EnvironmentScope.SHARED && env.variables.isEmpty()))) {
                edits += EnvironmentEdit.AddEnvironment(env.name)
            }
            for (name in old.orEmpty().keys) if (name !in desired) edits += EnvironmentEdit.RemoveVariable(env.name, name)
            for ((name, value) in desired) if (old?.get(name) != value) edits += EnvironmentEdit.SetVariable(env.name, name, value)
        }
        return edits
    }

    /** What the file of [scope] should hold for [env]: its variables of that file, and for a private variable the untouched shared copy in the shared file. */
    private fun desired(env: EditorEnvironment, scope: EnvironmentScope): Map<String, String> {
        val shared = env.original?.let { baseline.shared.environments[it] }.orEmpty()
        val out = LinkedHashMap<String, String>()
        for (v in env.variables) {
            when {
                scope == EnvironmentScope.PRIVATE && v.private -> out[v.name] = v.value
                scope == EnvironmentScope.SHARED && !v.private -> out[v.name] = v.value
                scope == EnvironmentScope.SHARED && wasInBoth(env, v.name) -> out[v.name] = shared.getValue(v.name)
            }
        }
        return out
    }

    /** Whether saving would change a file. */
    val isDirty: Boolean get() = EnvironmentScope.entries.any { edits(it).isNotEmpty() }

    /**
     * Why this draft cannot be saved: invalid or repeated names, and anything the files would refuse (judged by the same
     * `applying` the file store uses, so a variable that holds an object, say, is reported before saving). Empty when it can be saved.
     */
    fun check(): List<DraftProblem> {
        val problems = mutableListOf<DraftProblem>()
        val seen = mutableSetOf<String>()
        environments.forEachIndexed { i, env ->
            EnvironmentNames.environmentProblem(env.name)?.let { problems += DraftProblem(i, null, it) }
            if (!seen.add(env.name)) problems += DraftProblem(i, null, "There is already an environment named ${env.name}.")
            val names = mutableSetOf<String>()
            env.variables.forEachIndexed { j, v ->
                EnvironmentNames.variableProblem(v.name)?.let { problems += DraftProblem(i, j, it) }
                if (!names.add(v.name) && v.name.isNotEmpty()) problems += DraftProblem(i, j, "There is already a variable named ${v.name} in ${env.name}.")
            }
        }
        if (problems.isNotEmpty()) return problems
        for (scope in EnvironmentScope.entries) {
            val content = baseline.content(scope)
            try {
                content.environments.applying(edits(scope), content.hidden)
            } catch (e: EnvironmentEditException) {
                problems += DraftProblem(null, null, e.message ?: "The ${scope.name.lowercase()} file cannot take these changes.")
            }
        }
        return problems
    }

    companion object {
        internal val EMPTY_CONTENT = EnvironmentFileContent(emptyMap(), null, HiddenContent.NONE)

        /** The empty draft, before anything is loaded. */
        val EMPTY: EnvironmentDraft = EnvironmentDraft(emptyList(), Baseline(EMPTY_CONTENT, EMPTY_CONTENT))

        /** The draft showing [shared] and [private] as loaded: environments in file order (shared first), variables merged. */
        fun of(shared: EnvironmentFileContent, private: EnvironmentFileContent): EnvironmentDraft {
            val names: Set<String> = LinkedHashSet<String>().apply { addAll(shared.environments.keys); addAll(private.environments.keys) }
            val envs = names.map { name ->
                val s = shared.environments[name].orEmpty()
                val p = private.environments[name].orEmpty()
                val rows = s.map { (k, v) -> EditorVariable(k, p[k] ?: v, k in p) } +
                    p.filterKeys { it !in s }.map { (k, v) -> EditorVariable(k, v, true) }
                EditorEnvironment(name, rows, name, name in shared.hidden.notObjects || name in private.hidden.notObjects)
            }
            return EnvironmentDraft(envs, Baseline(shared, private))
        }
    }
}
