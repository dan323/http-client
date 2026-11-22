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
        val given = try { Path.of(path) } catch (_: java.nio.file.InvalidPathException) { throw PathRefusedException(UNAVAILABLE) }
        val candidates = if (given.isAbsolute) listOf(given) else roots.map { it.resolve(given) }
        for (c in candidates) {
            val real = try { c.normalize().toRealPath() } catch (_: IOException) { continue }
            // one answer for "outside the roots", "missing" and "not a .http file", so a path cannot be probed for existence
            if (roots.none { real.startsWith(it) } || !Files.isRegularFile(real)) continue
            val name = real.fileName.toString().lowercase()
            if (name.endsWith(".http") || name.endsWith(".rest")) return real
        }
        throw PathRefusedException(UNAVAILABLE)
    }

    private companion object { const val UNAVAILABLE = "No such .http or .rest file under the allowed roots" }
}
