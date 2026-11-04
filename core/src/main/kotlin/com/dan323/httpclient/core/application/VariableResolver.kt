package com.dan323.httpclient.core.application

object VariableResolver {
    // Names have no spaces, except dynamic variables (`$timestamp 1 d`, `$random.integer(1, 5)`).
    private val pattern = Regex("""\{\{\s*(\$[^{}]*?|[^{}\s]+)\s*}}""")

    /**
     * Replaces `{{name}}` from [variables], or `{{$uuid}}`-style dynamic variables;
     * throws if a variable is undefined so mistakes are not sent silently.
     * [extra] is consulted after [variables] and before the dynamic variables.
     */
    fun resolve(text: String, variables: Map<String, String>, extra: (String) -> String? = { null }, dynamic: DynamicVariables = DynamicVariables.DEFAULT): String =
        resolve(text, variables, extra, dynamic, 0)

    private fun resolve(text: String, variables: Map<String, String>, extra: (String) -> String?, dynamic: DynamicVariables, depth: Int): String =
        pattern.replace(text) { m ->
            val name = m.groupValues[1]
            val value = variables[name]
            when {
                // Variable values (file variables) may themselves hold references; the depth limit stops cycles.
                value != null -> if (depth < 10 && "{{" in value) resolve(value, variables, extra, dynamic, depth + 1) else value
                else -> extra(name) ?: dynamic.evaluate(name) ?: throw UndefinedVariableException(name)
            }
        }

    /**
     * Layers file variables over [base]: each value may use `{{...}}` of [base] and of earlier file
     * variables; a reference that cannot be resolved here is kept as written so it can resolve later
     * (for example a `{{name.response...}}` reference or a dynamic variable).
     */
    fun withFileVariables(base: Map<String, String>, fileVariables: Map<String, String>): Map<String, String> {
        if (fileVariables.isEmpty()) return base
        val result = base.toMutableMap()
        for ((name, value) in fileVariables) {
            result[name] = pattern.replace(value) { m ->
                val ref = m.groupValues[1]
                result[ref] ?: m.value
            }
        }
        return result
    }
}

class UndefinedVariableException(val variable: String) : RuntimeException("Undefined variable: $variable")
