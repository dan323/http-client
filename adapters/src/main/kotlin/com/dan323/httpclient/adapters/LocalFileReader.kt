package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.port.out.FileReader
import java.io.IOException
import java.nio.file.Files
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
) : FileReader {
    override fun read(path: String): ByteArray {
        val target = baseDir.resolve(path).toAbsolutePath().normalize()
        val real = realPath(target)
        val roots = (listOf(baseDir) + policy.extraRoots).map { realPath(it.toAbsolutePath().normalize()) }
        if (roots.none { real.startsWith(it) }) {
            throw FileAccessDeniedException(
                "File '$path' is outside the allowed folders (the .http file's folder${if (policy.extraRoots.isEmpty()) "" else " and the allowed roots"}); ${policy.allowHint}",
            )
        }
        return Files.readAllBytes(real)
    }

    /** Real path with symlinks resolved; for a missing file, the nearest existing ancestor is resolved and the rest appended. */
    private fun realPath(p: Path): Path {
        var existing: Path? = p
        while (existing != null && !Files.exists(existing)) existing = existing.parent
        if (existing == null) return p
        return existing.toRealPath().resolve(existing.relativize(p)).normalize()
    }
}
