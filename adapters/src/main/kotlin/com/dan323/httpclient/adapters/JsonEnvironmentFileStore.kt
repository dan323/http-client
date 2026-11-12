package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.domain.EnvironmentEdit
import com.dan323.httpclient.core.domain.EnvironmentEditException
import com.dan323.httpclient.core.domain.problem
import com.dan323.httpclient.core.port.out.EnvironmentFileChangedException
import com.dan323.httpclient.core.port.out.EnvironmentFileContent
import com.dan323.httpclient.core.port.out.EnvironmentFileException
import com.dan323.httpclient.core.port.out.EnvironmentFileStore
import com.dan323.httpclient.core.port.out.EnvironmentScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Edits `http-client.env.json` and `http-client.private.env.json` in [directory] without losing anything the editor does
 * not show: the file is parsed to a JSON object, only the entries an edit names are changed, and everything else (numbers
 * and booleans written as such, `null`, nested values, other keys, their order) is written back as it was. The output is
 * pretty-printed with two spaces, so the layout of a hand-formatted file can change; its content does not.
 *
 * A write goes through [AtomicReportWriter] (temp file and move; a new file is owner-only, an existing one keeps its
 * permissions). It is refused for a file that cannot be read, that is a link (the move would replace the link), or that
 * changed since it was read (compared by a SHA-256 of its bytes). Messages name files, environments and variables, never values.
 * The check and the write are not one atomic step: an editor saving in that instant can still be overwritten.
 */
class JsonEnvironmentFileStore(
    override val directory: Path,
    private val writer: AtomicReportWriter = AtomicReportWriter(),
) : EnvironmentFileStore {
    private class Snapshot(val root: JsonObject, val version: String?)

    private fun file(scope: EnvironmentScope): Path = directory.resolve(
        if (scope == EnvironmentScope.SHARED) JsonEnvironmentRepository.SHARED_FILE else JsonEnvironmentRepository.PRIVATE_FILE,
    )

    private fun snapshot(file: Path): Snapshot {
        if (!Files.exists(file)) {
            if (Files.isSymbolicLink(file)) throw EnvironmentFileException(file, "the link cannot be followed (broken or not accessible)")
            return Snapshot(JsonObject(emptyMap()), null)
        }
        val bytes = try { Files.readAllBytes(file) } catch (e: java.io.IOException) {
            throw EnvironmentFileException(file, e.message ?: "cannot read the file", e)
        }
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw EnvironmentFileException(file, "the file is not valid UTF-8 text")
        }
        return Snapshot(EnvironmentJson.parse(file, text), versionOf(bytes))
    }

    override fun read(scope: EnvironmentScope): EnvironmentFileContent {
        val s = snapshot(file(scope))
        return EnvironmentFileContent(EnvironmentJson.variables(s.root), s.version)
    }

    override fun write(scope: EnvironmentScope, edits: List<EnvironmentEdit>, expectedVersion: String?): String? {
        val file = file(scope)
        val current = snapshot(file)
        if (current.version != expectedVersion) throw EnvironmentFileChangedException(file)
        if (edits.isEmpty()) return current.version
        if (Files.isSymbolicLink(file)) throw EnvironmentFileException(file, "it is a link; edit the file it points to")
        val updated = edits.fold(current.root) { root, edit -> apply(root, edit) }
        val text = json.encodeToString(JsonElement.serializer(), updated) + "\n"
        writer.write(file, text)
        return versionOf(text.toByteArray(Charsets.UTF_8))
    }

    private fun apply(root: JsonObject, edit: EnvironmentEdit): JsonObject {
        edit.problem()?.let { throw EnvironmentEditException(it) }
        val entries = LinkedHashMap(root)
        when (edit) {
            is EnvironmentEdit.SetVariable -> {
                val env = environment(entries, edit.environment)
                val vars = LinkedHashMap(env)
                // the same text keeps the value as written: a number stays a number
                val kept = (vars[edit.name] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.content == edit.value }
                vars[edit.name] = kept ?: JsonPrimitive(edit.value)
                entries[edit.environment] = JsonObject(vars)
            }
            is EnvironmentEdit.RemoveVariable -> {
                val env = environment(entries, edit.environment)
                entries[edit.environment] = JsonObject(LinkedHashMap(env).also { it.remove(edit.name) })
            }
            is EnvironmentEdit.AddEnvironment -> {
                if (edit.name in entries) throw EnvironmentEditException("The environment ${edit.name} already exists.")
                entries[edit.name] = JsonObject(emptyMap())
            }
            is EnvironmentEdit.RenameEnvironment -> {
                if (edit.from !in entries) throw EnvironmentEditException("Unknown environment: ${edit.from}")
                if (edit.to in entries) throw EnvironmentEditException("The environment ${edit.to} already exists.")
                entries.clear()
                for ((k, v) in root) entries[if (k == edit.from) edit.to else k] = v
            }
            is EnvironmentEdit.RemoveEnvironment ->
                if (entries.remove(edit.name) == null) throw EnvironmentEditException("Unknown environment: ${edit.name}")
        }
        return JsonObject(entries)
    }

    private fun environment(entries: Map<String, JsonElement>, name: String): JsonObject {
        val env = entries[name] ?: throw EnvironmentEditException("Unknown environment: $name")
        return env as? JsonObject ?: throw EnvironmentEditException("The environment $name is not a JSON object, so its variables cannot be edited.")
    }

    private fun versionOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
    }
}
