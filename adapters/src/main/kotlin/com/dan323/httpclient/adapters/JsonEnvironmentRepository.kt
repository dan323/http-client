package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.domain.Environment
import com.dan323.httpclient.core.port.out.EnvironmentFileException
import com.dan323.httpclient.core.port.out.EnvironmentRepository
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads JetBrains-style `http-client.env.json` (shared) and
 * `http-client.private.env.json` (secrets; overrides shared values).
 * Malformed files raise [EnvironmentFileException] naming the file. A file for which [accept] says no is treated as absent
 * (checked for each of the two files separately, so a caller can refuse one and keep the other).
 */
class JsonEnvironmentRepository(private val dir: Path, private val accept: (Path) -> Boolean = { true }) : EnvironmentRepository {
    override fun load(): List<Environment> {
        val shared = read(dir.resolve(SHARED_FILE))
        val private = read(dir.resolve(PRIVATE_FILE))
        // `$shared` is not an environment: its variables apply to every environment (this client's own feature). Lowest to
        // highest precedence: public `$shared`, private `$shared`, public named environment, private named environment.
        val common = shared[SHARED_ENV].orEmpty() + private[SHARED_ENV].orEmpty()
        return (shared.keys + private.keys).filter { it != SHARED_ENV }.map { name ->
            Environment(name, common + (shared[name].orEmpty()) + (private[name].orEmpty()))
        }
    }

    private fun read(file: Path): Map<String, Map<String, String>> {
        if (!Files.exists(file) || !accept(file)) return emptyMap()
        return EnvironmentJson.variables(EnvironmentJson.parse(file, EnvironmentJson.readText(file)))
    }

    companion object {
        /** Name of the pseudo environment whose variables are added to all the others (this client's own feature; JetBrains does not document it). */
        const val SHARED_ENV = com.dan323.httpclient.core.domain.EnvironmentNames.SHARED
        const val SHARED_FILE = "http-client.env.json"
        const val PRIVATE_FILE = "http-client.private.env.json"
    }
}
