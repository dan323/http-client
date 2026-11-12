package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.port.out.EnvironmentFileException
import com.dan323.httpclient.core.port.out.EnvironmentFileStore
import com.dan323.httpclient.core.port.out.EnvironmentFileStoreLocator
import com.dan323.httpclient.core.port.out.EnvironmentLocator
import com.dan323.httpclient.core.port.out.EnvironmentRepository
import java.nio.file.Files
import java.nio.file.Path

/**
 * Nearest directory holding `http-client.env.json` or `http-client.private.env.json`, searching upward from the
 * `.http` file's own directory (which is always checked). The search stops after the first directory containing a
 * `.git` entry, and never enters a [boundaries] directory (default: the user's home and the system temp directory)
 * unless the `.http` file itself is in it, so a stray `~/http-client.env.json` cannot apply to unrelated projects.
 */
class UpwardEnvironmentLocator(
    private val boundaries: Set<Path> = defaultBoundaries(),
): EnvironmentLocator, EnvironmentFileStoreLocator {
    override fun find(httpFile: Path): EnvironmentRepository? = findDirectory(httpFile)?.let { JsonEnvironmentRepository(it) }

    /** The files to edit for [httpFile]: those of the folder [find] uses, or, when there are none, new ones next to the `.http` file. */
    override fun storeFor(httpFile: Path): EnvironmentFileStore =
        JsonEnvironmentFileStore(findDirectory(httpFile) ?: httpFile.toAbsolutePath().normalize().parent ?: Path.of("").toAbsolutePath())

    private fun findDirectory(httpFile: Path): Path? {
        val start = real(httpFile.toAbsolutePath().normalize().parent ?: return null)
        // Symlink aliases are resolved on both sides, so a link to home/temp cannot bypass the boundary.
        val resolvedBoundaries = boundaries.map(::real).toSet()
        var dir: Path? = start
        while (dir != null) {
            if (dir != start && dir in resolvedBoundaries) return null
            if (hasEnvFile(dir)) return dir
            if (Files.exists(dir.resolve(".git"))) return null
            dir = dir.parent
        }
        return null
    }

    private fun real(p: Path): Path = try { p.toRealPath() } catch (_: java.io.IOException) { p }

    private fun hasEnvFile(dir: Path): Boolean {
        val found = listOf(JsonEnvironmentRepository.SHARED_FILE, JsonEnvironmentRepository.PRIVATE_FILE)
            .map { isEnvFile(dir.resolve(it)) }
        return found.any { it }
    }

    /** A link that cannot be followed (dangling, or through a folder denying access) is an error, not "absent". */
    private fun isEnvFile(file: Path): Boolean {
        if (Files.isRegularFile(file)) return true
        if (Files.isSymbolicLink(file)) throw EnvironmentFileException(file, "the link cannot be followed (broken or not accessible)")
        return false
    }

    companion object {
        fun defaultBoundaries(): Set<Path> =
            listOfNotNull(System.getProperty("user.home"), System.getProperty("java.io.tmpdir"))
                .mapNotNull { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }.toSet()
    }
}
