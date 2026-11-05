package com.dan323.httpclient.adapters

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
) : EnvironmentLocator {
    override fun find(httpFile: Path): EnvironmentRepository? {
        val start = httpFile.toAbsolutePath().normalize().parent ?: return null
        var dir: Path? = start
        while (dir != null) {
            if (dir != start && dir in boundaries) return null
            if (hasEnvFile(dir)) return JsonEnvironmentRepository(dir)
            if (Files.exists(dir.resolve(".git"))) return null
            dir = dir.parent
        }
        return null
    }

    private fun hasEnvFile(dir: Path) =
        Files.isRegularFile(dir.resolve(JsonEnvironmentRepository.SHARED_FILE)) ||
            Files.isRegularFile(dir.resolve(JsonEnvironmentRepository.PRIVATE_FILE))

    companion object {
        fun defaultBoundaries(): Set<Path> =
            listOfNotNull(System.getProperty("user.home"), System.getProperty("java.io.tmpdir"))
                .mapNotNull { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }.toSet()
    }
}
