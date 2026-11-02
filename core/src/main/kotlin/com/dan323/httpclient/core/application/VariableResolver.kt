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
     */
    fun resolve(text: String, variables: Map<String, String>): String =
        pattern.replace(text) { m ->
            val name = m.groupValues[1]
            variables[name] ?: dynamic[name]?.invoke() ?: throw UndefinedVariableException(name)
        }
}

class UndefinedVariableException(val variable: String) : RuntimeException("Undefined variable: $variable")
