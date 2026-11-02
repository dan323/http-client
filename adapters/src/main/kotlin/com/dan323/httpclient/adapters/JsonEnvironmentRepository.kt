package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.domain.Environment
import com.dan323.httpclient.core.port.out.EnvironmentRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads JetBrains-style `http-client.env.json` (shared) and
 * `http-client.private.env.json` (secrets; overrides shared values).
 */
class JsonEnvironmentRepository(private val dir: Path) : EnvironmentRepository {
    override fun load(): List<Environment> {
        val shared = read(dir.resolve("http-client.env.json"))
        val private = read(dir.resolve("http-client.private.env.json"))
        return (shared.keys + private.keys).map { name ->
            Environment(name, (shared[name].orEmpty()) + (private[name].orEmpty()))
        }
    }

    private fun read(file: Path): Map<String, Map<String, String>> {
        if (!Files.exists(file)) return emptyMap()
        val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
        return root.mapValues { (_, env) ->
            (env as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.let { k to it.content }
            }.toMap()
        }
    }
}
