package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.domain.Environment
import com.dan323.httpclient.core.port.out.EnvironmentFileException
import com.dan323.httpclient.core.port.out.EnvironmentRepository
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads JetBrains-style `http-client.env.json` (shared) and
 * `http-client.private.env.json` (secrets; overrides shared values).
 * Malformed files raise [EnvironmentFileException] naming the file.
 */
class JsonEnvironmentRepository(private val dir: Path) : EnvironmentRepository {
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

    /** JSON `null` values are treated as undefined (skipped), never as the string "null". */
    private fun read(file: Path): Map<String, Map<String, String>> {
        if (!Files.exists(file)) return emptyMap()
        val root = try {
            Json.parseToJsonElement(Files.readString(file)) as? JsonObject
                ?: throw EnvironmentFileException(file, "expected a JSON object at the top level")
        } catch (e: EnvironmentFileException) {
            throw e
        } catch (e: SerializationException) {
            // Never use e.message or pass e as the cause: kotlinx quotes the input ("JSON input: ..."), which
            // would put secret values in error messages, reports and stack traces. Report the position only.
            throw EnvironmentFileException(file, malformedDetail(file, e.message))
        } catch (e: IOException) {
            throw EnvironmentFileException(file, e.message ?: "cannot read the file", e)
        }
        return root.mapValues { (_, env) ->
            (env as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { k to it.content }
            }.toMap()
        }
    }

    private fun malformedDetail(file: Path, kotlinxMessage: String?): String {
        val offset = kotlinxMessage?.lineSequence()?.firstOrNull()?.let { OFFSET.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: return "malformed JSON"
        val prefix = try { Files.readString(file).take(offset) } catch (_: IOException) { return "malformed JSON at offset $offset" }
        val line = prefix.count { it == '\n' } + 1
        val column = offset - (prefix.lastIndexOf('\n') + 1) + 1
        return "malformed JSON at line $line, column $column (offset $offset)"
    }

    companion object {
        private val OFFSET = Regex("""offset (\d+)""")
        /** Name of the pseudo environment whose variables are added to all the others (as in JetBrains). */
        const val SHARED_ENV = "\$shared"
        const val SHARED_FILE = "http-client.env.json"
        const val PRIVATE_FILE = "http-client.private.env.json"
    }
}
