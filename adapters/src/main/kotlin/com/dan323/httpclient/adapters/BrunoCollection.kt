package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.BrunoConverter
import com.dan323.httpclient.core.application.BrunoEnvironment
import com.dan323.httpclient.core.application.BrunoRequest
import com.dan323.httpclient.core.application.BrunoSettings
import com.dan323.httpclient.core.application.HttpFileWriter
import com.dan323.httpclient.core.domain.Environment
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** The `.bru` requests of one folder of a collection ([path] is relative to the collection, `""` for the root). */
data class BrunoFolder(val path: String, val requests: List<BrunoRequest>)

/** A Bruno collection read from disk; [warnings] name every file or part that was not converted. */
data class BrunoCollection(val folders: List<BrunoFolder>, val environments: List<BrunoEnvironment>, val warnings: List<String>)

/** What [BrunoCollections.convert] wrote: the files (relative to the output folder) and the warnings. */
data class BrunoConversion(val files: List<String>, val warnings: List<String>)

/**
 * Reads a Bruno collection (a folder with `bruno.json`) and converts it to `.http` files and an
 * `http-client.env.json`. Nothing is run and nothing outside the two folders is touched; symbolic links are not followed.
 */
object BrunoCollections {
    private const val MAX_FILE_BYTES = 1_000_000L

    /** The text of [file], or null when it cannot be read or is not valid UTF-8 (the caller reports it and goes on). */
    private fun readText(file: Path): String? = try { Files.readString(file) } catch (_: IOException) { null }

    fun isCollection(dir: Path): Boolean = Files.isRegularFile(dir.resolve("bruno.json"), LinkOption.NOFOLLOW_LINKS)

    /** Reads all `.bru` files below [dir]; throws [IOException] when [dir] is not a collection or cannot be read. */
    fun read(dir: Path): BrunoCollection {
        if (!isCollection(dir)) throw IOException("$dir is not a Bruno collection (no bruno.json)")
        val warnings = mutableListOf<String>()
        val folders = mutableListOf<BrunoFolder>()
        val environments = mutableListOf<BrunoEnvironment>()
        val envDir = dir.resolve("environments")
        if (Files.isDirectory(envDir, LinkOption.NOFOLLOW_LINKS)) {
            Files.list(envDir).use { it.toList() }.filter { it.fileName.toString().endsWith(".bru") && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .sortedBy { it.fileName.toString() }.forEach {
                    val envText = if (Files.size(it) > MAX_FILE_BYTES) null else readText(it)
                    if (envText == null) {
                        warnings += "environments/${it.fileName}: larger than $MAX_FILE_BYTES bytes or not readable as UTF-8 text, skipped"
                        return@forEach
                    }
                    val env = BrunoConverter.environment(it.fileName.toString().removeSuffix(".bru"), envText)
                    if (env.secrets.isNotEmpty()) {
                        warnings += "environments/${it.fileName}: secret values are not in the file; put ${env.secrets.joinToString(", ")} in http-client.private.env.json"
                    }
                    warnings += env.warnings.map { w -> "environments/${it.fileName}: $w" }
                    environments += env
                }
        }
        // collection.bru (root) and folder.bru (each folder) are read first: requests inherit them
        fun settingsOf(file: Path, shown: String, inherited: BrunoSettings): BrunoSettings {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return inherited
            val text = if (Files.size(file) > MAX_FILE_BYTES) null else readText(file)
            if (text == null) { warnings += "$shown: larger than $MAX_FILE_BYTES bytes or not readable as UTF-8 text, skipped"; return inherited }
            val own = BrunoConverter.settings(text)
            warnings += own.warnings.map { "$shown: $it" }
            return inherited.then(own)
        }
        // collection variables rank below the selected environment in Bruno, but a file variable would override it:
        // with environments they are copied into each one as defaults, without any they stay file variables
        val collectionVariables = LinkedHashMap<String, String>()
        fun walk(folder: Path, relative: String, outer: BrunoSettings) {
            val inherited = if (relative.isEmpty()) {
                val root = settingsOf(folder.resolve("collection.bru"), "collection.bru", outer)
                if (environments.isEmpty()) root else { collectionVariables += root.variables; root.copy(variables = emptyMap()) }
            } else settingsOf(folder.resolve("folder.bru"), "$relative/folder.bru", outer)
            val entries = Files.list(folder).use { it.toList() }.sortedBy { it.fileName.toString() }
            val requests = mutableListOf<BrunoRequest>()
            for (entry in entries) {
                val name = entry.fileName.toString()
                val shown = if (relative.isEmpty()) name else "$relative/$name"
                if (Files.isSymbolicLink(entry)) { warnings += "$shown: symbolic link skipped"; continue }
                if (Files.isDirectory(entry)) {
                    if (name.startsWith(".") || name == "node_modules" || (relative.isEmpty() && name == "environments")) {
                        if (relative.isNotEmpty() || name != "environments") warnings += "$shown: folder skipped"
                    } else walk(entry, shown, inherited)
                } else if (name.endsWith(".bru")) {
                    if ((name == "collection.bru" && relative.isEmpty()) || (name == "folder.bru" && relative.isNotEmpty())) {
                        // read above
                    } else if (name == "folder.bru" || name == "collection.bru") {
                        warnings += "$shown: ignored here (collection.bru belongs at the collection root, folder.bru in a folder)"
                    } else if (Files.size(entry) > MAX_FILE_BYTES) {
                        warnings += "$shown: larger than $MAX_FILE_BYTES bytes, skipped"
                    } else {
                        val text = readText(entry)
                        if (text == null) {
                            warnings += "$shown: not readable as UTF-8 text, skipped"
                        } else {
                            val depth = if (relative.isEmpty()) 0 else relative.count { it == '/' } + 1
                            val converted = BrunoConverter.request(text, name.removeSuffix(".bru"), "../".repeat(depth), inherited)
                            warnings += converted.warnings.map { "$shown: $it" }
                            requests += converted
                        }
                    }
                }
            }
            // stable order: Bruno's own sequence, then file name (the listing is already sorted by name)
            if (requests.isNotEmpty()) folders += BrunoFolder(relative, requests.sortedBy { it.sequence })
        }
        walk(dir, "", BrunoSettings())
        if (collectionVariables.isNotEmpty()) {
            warnings += "collection.bru: variables ${collectionVariables.keys.joinToString(", ")} were copied into each environment as defaults (an environment's own value wins, as in Bruno); without --env they are undefined"
        }
        val merged = environments.map { it.copy(environment = Environment(it.environment.name, collectionVariables + it.environment.variables)) }
        return BrunoCollection(folders, merged, warnings)
    }

    /**
     * Converts the collection at [source] into [target]: one `.http` file per folder with requests (`requests.http` at
     * the root, `<folder>/<folder>.http` below) and `http-client.env.json` when there are environments. [target] must
     * not exist or must be empty, so nothing is ever overwritten.
     */
    fun convert(source: Path, target: Path): BrunoConversion {
        val collection = read(source)
        if (Files.exists(target) && (!Files.isDirectory(target) || Files.list(target).use { it.findAny().isPresent })) {
            throw IOException("$target already exists and is not an empty folder")
        }
        val written = mutableListOf<String>()
        val warnings = collection.warnings.toMutableList()
        fun write(relative: String, text: String) {
            val file = target.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
            written += relative
        }
        for (folder in collection.folders) {
            val taken = mutableSetOf<String>()
            val requests = folder.requests.map { r ->
                var name = r.template.name ?: "request"
                var n = 2
                while (!taken.add(name)) name = "${r.template.name}-${n++}"
                r.template.copy(name = name)
            }
            val relative = if (folder.path.isEmpty()) "requests.http" else "${folder.path}/${folder.path.substringAfterLast('/')}.http"
            write(relative, HttpFileWriter.write(requests, listOf("Converted from the Bruno collection; see the notes printed by httpc convert-bruno.")))
        }
        if (collection.environments.isNotEmpty()) {
            write("http-client.env.json", EnvironmentJson.render(collection.environments.associate { it.environment.name to it.environment.variables }))
        }
        if (written.isEmpty()) warnings += "no requests or environments found"
        return BrunoConversion(written, warnings)
    }
}
