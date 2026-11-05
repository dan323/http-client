package com.dan323.httpclient.adapters

import java.io.File
import java.nio.file.Path

/**
 * Which directories `< path` / `<@ path` bodies may be read from, besides the folder of the .http file
 * (always allowed). Mirrors the `$env` allowlist ([SystemEnvironmentVariables]): off by default, opened
 * explicitly per front end.
 */
class FileAccessPolicy(
    val extraRoots: List<Path> = emptyList(),
    val allowHint: String = DEFAULT_HINT,
) {
    companion object {
        /** System property listing extra allowed directories, separated by the platform path separator (`:`, or `;` on Windows). */
        const val ALLOW_PROPERTY = "httpclient.allowFileRoots"

        /** Environment variable fallback for [ALLOW_PROPERTY]; same separator. */
        const val ALLOW_ENV_VAR = "HTTPC_ALLOW_FILE_ROOTS"

        val DEFAULT_HINT = "add the directory to the system property -D$ALLOW_PROPERTY=DIR or the environment variable " +
            "$ALLOW_ENV_VAR=DIR (separate several with '${File.pathSeparator}')"

        const val CLI_HINT = "pass --allow-file-root DIR"

        /** Splits a path list such as `/a:/b` (or `C:\a;D:\b` on Windows); blank entries are dropped. */
        fun parseRoots(text: String?, separator: String = File.pathSeparator): List<Path> =
            text.orEmpty().split(separator).map { it.trim() }.filter { it.isNotEmpty() }.map { Path.of(it) }

        /** Policy from [ALLOW_PROPERTY], else [ALLOW_ENV_VAR]. For front ends without command-line flags. */
        fun fromConfiguration(
            property: (String) -> String? = System::getProperty,
            env: (String) -> String? = System::getenv,
        ): FileAccessPolicy = FileAccessPolicy(parseRoots(property(ALLOW_PROPERTY) ?: env(ALLOW_ENV_VAR)))
    }
}
