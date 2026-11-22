package com.dan323.httpclient.mcp

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** A tool was given a path it may not use; the message never says more than "outside the roots" or "not found". */
class PathRefusedException(message: String) : RuntimeException(message)

/**
 * Turns the path an agent sent into a real `.http` file inside a root. Symbolic links are resolved first, so a link
 * out of a root is refused. Only `.http` and `.rest` files qualify: the tools never read anything else.
 */
class PathGuard(roots: List<Path>) {
    private val roots = roots.map { it.toRealPath() }

    fun resolve(path: String): Path {
        val given = try { Path.of(path) } catch (_: java.nio.file.InvalidPathException) { throw PathRefusedException("Invalid path") }
        val candidates = if (given.isAbsolute) listOf(given) else roots.map { it.resolve(given) }
        var found: Path? = null
        for (c in candidates) {
            val real = try { c.normalize().toRealPath() } catch (_: IOException) { continue }
            if (roots.none { real.startsWith(it) }) throw PathRefusedException("Path is outside the allowed roots")
            found = real
            break
        }
        val file = found ?: throw PathRefusedException("File not found under the allowed roots")
        val name = file.fileName.toString().lowercase()
        if (!Files.isRegularFile(file) || !(name.endsWith(".http") || name.endsWith(".rest"))) {
            throw PathRefusedException("Only .http and .rest files can be used")
        }
        return file
    }
}
