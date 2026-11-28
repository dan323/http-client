package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.port.out.Allowlists

/**
 * The allowlists as edited in a settings form of the desktop app or the IDE plugin: two texts with one entry per line
 * (environment variable names, then folders). Blank lines and surrounding spaces are ignored and repeats are dropped.
 * The form saves only what [check] accepts.
 */
object AllowlistForm {
    /** The checked result: the [Allowlists] to save, or the [errors] to show (one per bad entry). */
    sealed interface Checked {
        data class Valid(val allowlists: Allowlists) : Checked
        data class Invalid(val errors: List<String>) : Checked
    }

    /** The text that shows [entries] in the form. */
    fun text(entries: List<String>): String = entries.joinToString("\n")

    /** The entries of a form text: trimmed, without blank lines and repeats, in order. */
    fun entries(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** What the form texts would save, unchecked: compare with the saved value to know whether the form changed. */
    fun parse(envText: String, rootsText: String): Allowlists = Allowlists(entries(envText), entries(rootsText))

    /**
     * Why [name] can never be read by `{{$env.NAME}}`, or null: a space ends the reference, `,` separates names in
     * `HTTPC_ALLOW_ENV`, `=` is never part of a variable name, and braces would end or nest the reference.
     */
    fun envNameProblem(name: String): String? = when {
        name.any { it.isWhitespace() } -> "Environment variable '$name': a name cannot contain spaces"
        name.any { it in ",={}" } -> "Environment variable '$name': a name cannot contain ',', '=', '{' or '}'"
        else -> null
    }

    /** Checks both texts; [rootProblem] says why a folder cannot be allowed (the front end checks it the way runs do). */
    fun check(envText: String, rootsText: String, rootProblem: (String) -> String?): Checked {
        val parsed = parse(envText, rootsText)
        val errors = parsed.envNames.mapNotNull(::envNameProblem) + parsed.fileRoots.mapNotNull(rootProblem)
        return if (errors.isEmpty()) Checked.Valid(parsed) else Checked.Invalid(errors)
    }
}
