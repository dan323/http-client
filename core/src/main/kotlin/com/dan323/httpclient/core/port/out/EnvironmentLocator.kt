package com.dan323.httpclient.core.port.out

import java.nio.file.Path

/**
 * Finds the environments that apply to a `.http` file. The one rule, shared by every front end: the nearest
 * enclosing directory (the file's own, then its parents) that contains `http-client.env.json` or
 * `http-client.private.env.json`; both files of that directory are merged. The search stops after the first
 * directory containing a `.git` entry and never enters the user's home directory (or anything above it) unless the
 * `.http` file is in that directory. No such directory means no environments.
 */
fun interface EnvironmentLocator {
    /** The repository for [httpFile], or null when neither `http-client.env.json` nor
     * `http-client.private.env.json` is found within the search boundaries. Throws [EnvironmentFileException]
     * when the nearest candidate is a link that cannot be followed. */
    fun find(httpFile: Path): EnvironmentRepository?
}

/** An environment file exists but cannot be read; the message names the file (and the position, when known). */
class EnvironmentFileException(val file: Path, detail: String, cause: Throwable? = null) :
    RuntimeException("Invalid environment file $file: $detail", cause)
