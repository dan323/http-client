package com.dan323.httpclient.core.application

object VariableResolver {
    private val pattern = Regex("""\{\{\s*([^{}\s]+)\s*}}""")

    /** Replaces `{{name}}`; throws if a variable is undefined so mistakes are not sent silently. */
    fun resolve(text: String, variables: Map<String, String>): String =
        pattern.replace(text) { m ->
            variables[m.groupValues[1]]
                ?: throw UndefinedVariableException(m.groupValues[1])
        }
}

class UndefinedVariableException(val variable: String) : RuntimeException("Undefined variable: $variable")
