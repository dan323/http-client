package com.dan323.httpclient.core.application

object VariableResolver {
    // Names have no spaces, except dynamic variables: `$` + a letter, then `(args...)`, or for the built-in
    // ones with arguments a space and the rest (`$timestamp 1 d`, `$randomInt 1 6`), or `$processEnv NAME`.
    // Anything else (`{{$ x}}`, `{{$x := 1}}`, multi-line text) is left as written, as before.
    // Possessive quantifiers keep matching linear (no catastrophic backtracking on an unclosed `{{$uuid` followed
    // by a long run of whitespace). The captured name may therefore end in spaces/tabs; [nameOf] trims it.
    // Whitespace before `(` is horizontal only, so a reference never spans lines.
    internal val pattern = Regex(
        """\{\{\s*+(\${'$'}processEnv[ \t]++[^{}\s]++""" +
            """|\${'$'}(?:(?:timestamp|isoTimestamp|randomInt|uuid|random\.\w++)(?:[ \t]*+\([^{}\r\n]*+|[ \t]++[^{}\r\n]*+)?""" +
            """|[A-Za-z][\w.]*+(?:[ \t]*+\([^{}\r\n]*+)?)""" +
            """|[^{}\s]++)\s*+}}""",
    )

    internal fun nameOf(m: MatchResult): String = m.groupValues[1].trimEnd()

    private const val MAX_EXPANSIONS = 10_000
    private const val MAX_DEPTH = 100
    private const val MAX_LENGTH = 16 * 1024 * 1024

    /**
     * Replaces `{{name}}` from [fileVariables], then [variables], then [extra], then the dynamic variables;
     * throws if a variable is undefined so mistakes are not sent silently.
     *
     * Only values of [fileVariables] (written in the `.http` file) are themselves expanded; values of
     * [variables] (environment) and whatever [extra] or a dynamic variable returns are literal, so a secret
     * containing `{{` is sent as is. Inside a file variable's value a reference to the same name means the
     * environment value of that name (`@host = {{host}}/api`); a reference to an earlier file variable, an
     * environment variable or a later file variable is looked up in that order. A cycle fails with
     * [VariableExpansionException]; one resolve call expands at most [MAX_EXPANSIONS] file variable values
     * (a value without dynamic parts is expanded once per call and reused; the limit stops exponential fan-out) and [MAX_DEPTH] levels, otherwise it fails the same way.
     */
    fun resolve(
        text: String,
        variables: Map<String, String>,
        extra: (String) -> String? = { null },
        dynamic: DynamicVariables = DynamicVariables.DEFAULT,
        fileVariables: Map<String, String> = emptyMap(),
        onSubstitute: (name: String, value: String) -> Unit = { _, _ -> },
    ): String = Expansion(variables, fileVariables, extra, dynamic, onSubstitute).expand(text, null)

    private class Expansion(
        private val variables: Map<String, String>,
        private val fileVariables: Map<String, String>,
        private val extra: (String) -> String?,
        private val dynamic: DynamicVariables,
        private val onSubstitute: (String, String) -> Unit,
    ) {
        private val position = fileVariables.keys.withIndex().associate { it.value to it.index }
        private val stack = ArrayList<String>()
        private var budget = MAX_EXPANSIONS
        // Expansion of a file variable depends only on its name, so it is cached unless it used extra/dynamic
        // values, which are re-evaluated on every use ($uuid, $timestamp, response references).
        private val cache = HashMap<String, String>()
        private var impure = 0

        fun expand(text: String, owner: String?): String = pattern.replace(text) { m ->
            val name = nameOf(m)
            lookup(name, owner).also { onSubstitute(name, it) }
        }

        private fun lookup(name: String, owner: String?): String {
            val file = fileVariables[name]
            if (file != null && LookupOrder.fileBeforeEnvironment(name, owner) { position.getValue(it) }) {
                return expandFile(name, file)
            }
            variables[name]?.let { return it }
            if (file != null && LookupOrder.fileAfterEnvironment(name, owner)) return expandFile(name, file)
            extra(name)?.let { impure++; return it }
            dynamic.evaluate(name)?.let { impure++; return it }
            // Only a file variable referring to its own name gets here: it means the environment's value, which is missing.
            // (Real cycles are reported by expandFile.)
            throw UndefinedVariableException(name)
        }

        private fun expandFile(name: String, value: String): String {
            cache[name]?.let { return it }
            if (name in stack) {
                throw VariableExpansionException("Circular variable reference: ${(stack.subList(stack.indexOf(name), stack.size) + name).joinToString(" -> ")}")
            }
            if (stack.size >= MAX_DEPTH) throw VariableExpansionException("Variables are nested more than $MAX_DEPTH levels deep (at '$name')")
            if (--budget < 0) throw VariableExpansionException("Variable expansion too large (more than $MAX_EXPANSIONS expansions, at '$name')")
            stack.add(name)
            val before = impure
            try {
                val result = expand(value, name)
                if (result.length > MAX_LENGTH) throw VariableExpansionException("Variable expansion too large (more than $MAX_LENGTH characters, at '$name')")
                if (impure == before) cache[name] = result
                return result
            } finally {
                stack.removeAt(stack.size - 1)
            }
        }
    }
}

/** A variable cannot be expanded: it refers to itself (directly or through others) or expands too much. */
class VariableExpansionException(message: String) : RuntimeException(message)

class UndefinedVariableException(val variable: String) : RuntimeException("Undefined variable: $variable")

/**
 * Where a file variable sits in the lookup order of [VariableResolver]; shared with `VariableAnalysis` so the
 * editor diagnostics cannot drift from run time. [owner] is the file variable whose value is being expanded
 * (null for request text).
 */
internal object LookupOrder {
    /** A file variable wins over the environment in request text, and in a value when it was defined before [owner]. */
    inline fun fileBeforeEnvironment(name: String, owner: String?, positionOf: (String) -> Int): Boolean =
        owner == null || name != owner && positionOf(name) < positionOf(owner)

    /** Otherwise a file variable is still found after the environment, unless it is the owner itself. */
    fun fileAfterEnvironment(name: String, owner: String?): Boolean = name != owner
}
