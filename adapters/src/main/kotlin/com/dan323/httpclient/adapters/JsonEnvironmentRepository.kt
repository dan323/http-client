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
        return (shared.keys + private.keys).map { name ->
            Environment(name, (shared[name].orEmpty()) + (private[name].orEmpty()))
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
            throw EnvironmentFileException(file, e.message ?: "malformed JSON", e)
        } catch (e: IOException) {
            throw EnvironmentFileException(file, e.message ?: "cannot read the file", e)
        }
        return root.mapValues { (_, env) ->
            (env as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { k to it.content }
            }.toMap()
        }
    }

    companion object {
        const val SHARED_FILE = "http-client.env.json"
        const val PRIVATE_FILE = "http-client.private.env.json"
    }
}
