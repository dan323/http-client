package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.port.out.EnvironmentVariables

/**
 * Reads the OS process environment for `{{$env.NAME}}`, but only the names in [allowed]: an untrusted
 * `.http` file must not be able to send `AWS_SECRET_ACCESS_KEY` somewhere. Allowlist names are matched exactly; on Windows the lookup of an allowed name ignores case.
 */
class SystemEnvironmentVariables(
    private val allowed: Set<String> = emptySet(),
    override val allowHint: String = DEFAULT_HINT,
    private val source: () -> Map<String, String> = System::getenv,
    private val windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
) : EnvironmentVariables {
    override fun isAllowed(name: String): Boolean = name in allowed

    override fun get(name: String): String? {
        if (!isAllowed(name)) return null
        val env = source()
        // Windows environment names are case-insensitive (`Path` vs `PATH`); the allowlist itself stays exact.
        return env[name] ?: if (windows) env.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value else null
    }

    companion object {
        /** System property listing the allowed names (comma separated); used by the desktop app and the IDE plugin. */
        const val ALLOW_PROPERTY = "httpclient.allowEnv"

        /** Process environment variable listing the allowed names (comma separated); unioned with [ALLOW_PROPERTY]. */
        const val ALLOW_ENV_VAR = "HTTPC_ALLOW_ENV"

        const val DEFAULT_HINT = "add it to the system property -D$ALLOW_PROPERTY=NAME or the environment variable $ALLOW_ENV_VAR=NAME (comma separated)"

        /** Splits `A, B,,C` into `{A, B, C}`. */
        fun parseNames(text: String?): Set<String> =
            text.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        /** Union of [ALLOW_PROPERTY], [ALLOW_ENV_VAR] and [extra]. For front ends without command-line flags. */
        fun fromConfiguration(
            property: (String) -> String? = System::getProperty,
            env: (String) -> String? = System::getenv,
            extra: Set<String> = emptySet(),
            hint: String = DEFAULT_HINT,
        ): SystemEnvironmentVariables =
            SystemEnvironmentVariables(parseNames(property(ALLOW_PROPERTY)) + parseNames(env(ALLOW_ENV_VAR)) + extra, hint)
    }
}
