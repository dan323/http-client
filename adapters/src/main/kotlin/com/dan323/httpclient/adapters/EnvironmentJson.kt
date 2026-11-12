package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.port.out.EnvironmentFileException
import kotlinx.serialization.SerializationException
import com.dan323.httpclient.core.domain.HiddenContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

    /** What the editable view leaves out of [root]: environments that are not objects, and variables holding objects or arrays (a `null` counts as undefined and may be replaced). */
    fun hidden(root: JsonObject): HiddenContent = HiddenContent(
        notObjects = root.filterValues { it !is JsonObject }.keys,
        hidden = root.mapNotNull { (name, env) ->
            (env as? JsonObject)?.filterValues { it is JsonObject || it is JsonArray }?.keys?.takeIf { it.isNotEmpty() }?.let { name to it }
        }.toMap(),
    )

    /**
     * The first object key that appears twice in the same object of [text] (the key and its line), or null. The JSON parser
     * keeps only the last of two equal keys, so an editor that writes the parsed tree back would lose the other one. Assumes
     * [text] is well-formed JSON (it was parsed before); the value is never reported.
     */
    fun duplicateKey(text: String): Pair<String, Int>? {
        // one entry per open container: the keys seen so far for an object, null for an array
        val stack = ArrayDeque<MutableSet<String>?>()
        var line = 1
        var expectKey = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\n' -> line++
                c == '{' -> { stack.addLast(HashSet()); expectKey = true }
                c == '[' -> stack.addLast(null)
                c == '}' || c == ']' -> { stack.removeLastOrNull(); expectKey = false }
                c == ',' -> expectKey = stack.lastOrNull() != null
                c == ':' -> expectKey = false
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\' && i + 1 < text.length) { sb.append(text[i]); i++ }
                        if (text[i] == '\n') line++
                        sb.append(text[i]); i++
                    }
                    val keys = stack.lastOrNull()
                    if (expectKey && keys != null) {
                        if (!keys.add(sb.toString())) return sb.toString() to line
                        expectKey = false
                    }
                }
            }
            i++
        }
        return null
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
