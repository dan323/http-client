package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.RequestTimeouts

/**
 * The total timeout for requests without `# @timeout`, for front ends without a command-line flag (desktop, plugin):
 * the system property [PROPERTY], else the environment variable [ENV_VAR], else [RequestTimeouts.DEFAULT_MILLIS].
 * The value is a whole number with an optional `ms`, `s` or `m` suffix, like `# @timeout`.
 */
object DefaultTimeout {
    const val PROPERTY = "httpclient.timeout"
    const val ENV_VAR = "HTTPC_TIMEOUT"

    /** A bad value comes back as a failure carrying the message to show the user (never silently the default). */
    fun load(
        property: (String) -> String? = System::getProperty,
        env: (String) -> String? = System::getenv,
    ): Result<Long> {
        val raw = property(PROPERTY)?.takeIf { it.isNotBlank() } ?: env(ENV_VAR)?.takeIf { it.isNotBlank() } ?: return Result.success(RequestTimeouts.DEFAULT_MILLIS)
        return try {
            Result.success(RequestTimeouts.parseMillis(raw, raw, "$PROPERTY / $ENV_VAR"))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }
}
