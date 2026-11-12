package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.port.out.EnvironmentFileException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** Reading the JSON of an environment file, shared by the repository (for running requests) and the file store (for editing). */
internal object EnvironmentJson {
    private val OFFSET = Regex("""offset (\d+)""")

    /** The text of [file]; an I/O problem (or a file that is not text) raises [EnvironmentFileException] naming the file. */
    fun readText(file: Path): String = try {
        Files.readString(file)
    } catch (e: java.io.IOException) {
        throw EnvironmentFileException(file, e.message ?: "cannot read the file", e)
    }

    /**
     * The top-level object of [text], the content of [file]. A file that is not a JSON object, or is malformed, raises
     * [EnvironmentFileException] naming the file and the position, never the content (kotlinx quotes the input in its
     * messages, which would put secret values in errors, reports and stack traces).
     */
    fun parse(file: Path, text: String): JsonObject = try {
        Json.parseToJsonElement(text) as? JsonObject
            ?: throw EnvironmentFileException(file, "expected a JSON object at the top level")
    } catch (e: EnvironmentFileException) {
        throw e
    } catch (e: SerializationException) {
        throw EnvironmentFileException(file, malformedDetail(text, e.message))
    }

    /** Environment name to its variables with a text, number or boolean value; JSON `null` and other values are treated as undefined. */
    fun variables(root: JsonObject): Map<String, Map<String, String>> = root.mapValues { (_, env) ->
        (env as? JsonObject).orEmpty().mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { k to it.content }
        }.toMap()
    }

    private fun malformedDetail(text: String, kotlinxMessage: String?): String {
        val offset = kotlinxMessage?.lineSequence()?.firstOrNull()?.let { OFFSET.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: return "malformed JSON"
        val prefix = text.take(offset)
        val line = prefix.count { it == '\n' } + 1
        val column = offset - (prefix.lastIndexOf('\n') + 1) + 1
        return "malformed JSON at line $line, column $column (offset $offset)"
    }
}
