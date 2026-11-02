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
        pattern.replace(text) { m ->
            val name = m.groupValues[1]
            variables[name] ?: extra(name) ?: dynamic[name]?.invoke() ?: throw UndefinedVariableException(name)
        }
}

class UndefinedVariableException(val variable: String) : RuntimeException("Undefined variable: $variable")
