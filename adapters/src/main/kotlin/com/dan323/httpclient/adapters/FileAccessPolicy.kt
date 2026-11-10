>> Line Coverage (for mutated classes only): 1734/1841 (94%)
>> 148 tests examined
>> Generated 1489 mutations Killed 1183 (79%)
>> Mutations with no coverage 122. Test strength 87%
>> Ran 4735 tests (3.18 tests per mutation)
Enhanced functionality available at https://www.arcmutate.com/

Build messages:-
* Project uses kotlin, but the Arcmutate kotlin plugin is not present. (https://docs.arcmutate.com/docs/kotlin.html)


> Task :core:pitest FAILED
FAILURE: Build failed with an exception.package com.dan323.httpclient.adapters

import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** An allowed-root setting (property, env var or CLI flag) is unusable; the message names the entry. */
class FileAccessConfigException(message: String) : IllegalArgumentException(message)

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

        /**
         * Splits a path list such as `/a:/b` (or `C:\a;D:\b` on Windows); blank entries are dropped. A leading
         * `~` is expanded to [home]. Every entry must be an absolute path (relative ones would resolve against the
         * arbitrary working directory of the IDE/desktop process) and not a filesystem root; otherwise
         * [FileAccessConfigException] names the entry.
         */
        fun parseRoots(
            text: String?,
            separator: String = File.pathSeparator,
            home: String? = System.getProperty("user.home"),
        ): List<Path> =
            text.orEmpty().split(separator).map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
                validateRoot(entry, requireAbsolute = true, home = home)
            }

        /**
         * Parses and checks one allowed root. Rejects invalid paths, filesystem roots (`/`, `C:\`, which would turn
         * confinement off) and, when [requireAbsolute], relative paths. A root equal to the home directory is accepted
         * with a warning on stderr.
         */
        fun validateRoot(entry: String, requireAbsolute: Boolean, home: String? = System.getProperty("user.home")): Path {
            if (entry.isBlank()) throw FileAccessConfigException("Empty allowed file root (check the variable that produced it)")
            val expanded = when {
                entry == "~" -> home ?: throw FileAccessConfigException("Cannot expand '~' in allowed file root '$entry': no home directory")
                entry.startsWith("~/") || entry.startsWith("~\\") ->
                    (home ?: throw FileAccessConfigException("Cannot expand '~' in allowed file root '$entry': no home directory")) + entry.substring(1)
                else -> entry
            }
            val path = try { Path.of(expanded) } catch (e: InvalidPathException) {
                throw FileAccessConfigException("Invalid allowed file root '$entry': ${e.reason}")
            }
            if (requireAbsolute && !path.isAbsolute) {
                throw FileAccessConfigException("Allowed file root '$entry' must be an absolute path (use ~ for the home directory)")
            }
            val abs = path.toAbsolutePath().normalize()
            if (abs.parent == null) {
                throw FileAccessConfigException("Allowed file root '$entry' is a filesystem root, which would disable file confinement; name a specific directory")
            }
            if (home != null && runCatching { abs == Path.of(home).toAbsolutePath().normalize() }.getOrDefault(false)) {
                System.err.println("warning: allowed file root '$entry' is your home directory; every file in it can be read by `< path` bodies")
            }
            return path
        }

        /** Policy from [ALLOW_PROPERTY], else [ALLOW_ENV_VAR]. For front ends without command-line flags. Throws [FileAccessConfigException]. */
        fun fromConfiguration(
            property: (String) -> String? = System::getProperty,
            env: (String) -> String? = System::getenv,
        ): FileAccessPolicy = FileAccessPolicy(parseRoots(property(ALLOW_PROPERTY) ?: env(ALLOW_ENV_VAR)))

        /** Like [fromConfiguration], but a bad setting is returned as a failure carrying the message to show the user. */
        fun load(
            property: (String) -> String? = System::getProperty,
            env: (String) -> String? = System::getenv,
        ): Result<FileAccessPolicy> =
            try { Result.success(fromConfiguration(property, env)) } catch (e: FileAccessConfigException) { Result.failure(e) }
    }
}
