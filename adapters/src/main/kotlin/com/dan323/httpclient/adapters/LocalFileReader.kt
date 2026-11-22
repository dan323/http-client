package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.port.out.FileReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Raised when a `< path` body points outside the allowed directories. The message never contains file contents. */
class FileAccessDeniedException(message: String) : IOException(message)

/**
 * Resolves `< path` bodies relative to the directory of the .http file and refuses anything that ends up
 * outside that directory (or the [policy]'s extra roots), after `..` normalisation and symlink resolution.
 */
class LocalFileReader(
    private val baseDir: Path,
    private val policy: FileAccessPolicy = FileAccessPolicy(),
    /** Refuse `http-client.env.json` and `http-client.private.env.json` whatever the roots, so a body cannot echo a secret file back. */
    private val refuseEnvironmentFiles: Boolean = false,
) : FileReader {
    override fun read(path: String): ByteArray {
        val target = baseDir.resolve(path).toAbsolutePath().normalize()
        val real = realPathOf(target)
        if (refuseEnvironmentFiles && listOf(target, real).any { isEnvironmentFile(it) }) {
            throw FileAccessDeniedException("File '$path' is an environment file and cannot be read as a body here")
        }
        val roots = (listOf(baseDir) + policy.extraRoots).map { realPathOf(it.toAbsolutePath().normalize()) }
        if (roots.none { real.startsWith(it) }) {
            throw FileAccessDeniedException(
                "File '$path' is outside the allowed folders (the .http file's folder${if (policy.extraRoots.isEmpty()) "" else " and the allowed roots"}); ${policy.allowHint}",
            )
        }
        // Open the resolved path without following a final symlink, then re-verify it still resolves to the same place.
        // Residual risk: an attacker able to write inside the allowed folders can still race the check/open window
        // (e.g. swap a parent directory for a symlink); full protection would need openat-style APIs the JDK lacks.
        Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS).use { input ->
            if (Files.exists(real) && real.toRealPath() != real) {
                throw FileAccessDeniedException("File '$path' changed while being opened; refusing to read it")
            }
            return input.readAllBytes()
        }
    }
}

private fun isEnvironmentFile(p: Path): Boolean = p.fileName?.toString()?.lowercase() in
    setOf(JsonEnvironmentRepository.SHARED_FILE, JsonEnvironmentRepository.PRIVATE_FILE)

/** Real path with symlinks resolved; for a missing file, the nearest existing ancestor is resolved and the rest appended. */
internal fun realPathOf(p: Path): Path = realPathOf(p, 0)

private fun realPathOf(p: Path, depth: Int): Path {
    var existing: Path? = p
    while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.parent
    if (existing == null) return p
    if (!Files.exists(existing) && Files.isSymbolicLink(existing)) {
        // Dangling symlink: judge it by where it points, so a link to an outside target is denied like an existing one.
        if (depth > 32) return p
        val target = (existing.parent ?: existing).resolve(Files.readSymbolicLink(existing)).normalize()
        return realPathOf(target.resolve(existing.relativize(p)).normalize(), depth + 1)
    }
    return existing.toRealPath().resolve(existing.relativize(p)).normalize()
}
