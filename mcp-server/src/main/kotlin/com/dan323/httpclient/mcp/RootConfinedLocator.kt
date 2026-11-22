package com.dan323.httpclient.mcp

import com.dan323.httpclient.adapters.JsonEnvironmentRepository
import com.dan323.httpclient.core.port.out.EnvironmentLocator
import com.dan323.httpclient.core.port.out.EnvironmentRepository
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds `http-client.env.json` / `http-client.private.env.json` like the CLI does (nearest folder upward from the
 * `.http` file) but never leaves the root that holds the file, so a file in `--root /work/app/requests` cannot pull in
 * `/work/app/http-client.private.env.json` (rule 1 of docs/mcp-safety-model.md). An environment file that is a symbolic
 * link out of the root is ignored.
 */
class RootConfinedLocator(roots: List<Path>) : EnvironmentLocator {
    private val roots = roots.map { it.toRealPath() }

    override fun find(httpFile: Path): EnvironmentRepository? {
        val file = try { httpFile.toRealPath() } catch (_: IOException) { return null }
        val root = roots.firstOrNull { file.startsWith(it) } ?: return null
        var dir = file.parent
        while (dir != null && dir.startsWith(root)) {
            if (listOf(JsonEnvironmentRepository.SHARED_FILE, JsonEnvironmentRepository.PRIVATE_FILE).any { inside(dir!!.resolve(it), root) }) {
                // each of the two files is vetted when it is read: one that is a link out of the root is ignored, the other stays usable
                return JsonEnvironmentRepository(dir) { inside(it, root) }
            }
            dir = dir.parent
        }
        return null
    }

    private fun inside(file: Path, root: Path): Boolean = try {
        Files.isRegularFile(file) && file.toRealPath().startsWith(root)
    } catch (_: IOException) { false }
}
