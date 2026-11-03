package com.dan323.httpclient.core.application

import java.time.Instant
import java.util.UUID
import kotlin.random.Random

object VariableResolver {
    private val pattern = Regex("""\{\{\s*([^{}\s]+)\s*}}""")

    /** JetBrains-style dynamic variables; each occurrence is evaluated afresh. */
    private val dynamic: Map<String, () -> String> = mapOf(
        "\$uuid" to { UUID.randomUUID().toString() },
        "\$timestamp" to { Instant.now().epochSecond.toString() },
        "\$isoTimestamp" to { Instant.now().toString() },
        "\$randomInt" to { Random.nextInt(0, 1000).toString() },
    )

    /**
     * Replaces `{{name}}` from [variables], or `{{$uuid}}`-style dynamic variables;
     * throws if a variable is undefined so mistakes are not sent silently.
     * [extra] is consulted after [variables] and before the dynamic variables.
     */
    fun resolve(text: String, variables: Map<String, String>, extra: (String) -> String? = { null }): String =
        resolve(text, variables, extra, 0)

    private fun resolve(text: String, variables: Map<String, String>, extra: (String) -> String?, depth: Int): String =
        pattern.replace(text) { m ->
            val name = m.groupValues[1]
            val value = variables[name]
            when {
                // Variable values (file variables) may themselves hold references; the depth limit stops cycles.
                value != null -> if (depth < 10 && "{{" in value) resolve(value, variables, extra, depth + 1) else value
                else -> extra(name) ?: dynamic[name]?.invoke() ?: throw UndefinedVariableException(name)
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
