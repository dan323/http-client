package com.dan323.httpclient.viewmodel

import java.nio.file.Files
import java.nio.file.Path

/** One line of a folder view: a sub-folder or a `.http` file. [key] identifies the file (its real path) so a view can tell which one is open without touching the disk. */
class FolderEntry(val path: Path, val name: String, val directory: Boolean, val key: String = path.toString())

/** What one folder holds for the view: [entries] (at most [FolderListing.MAX_ENTRIES] folders and as many files), how many more there were, and why it could not be read. */
class FolderContents(val entries: List<FolderEntry>, val hidden: Int = 0, val error: String? = null)

/**
 * Lists a folder for a file tree: sub-folders first, then `.http` files, each sorted by name ignoring case; folders that
 * usually hold generated or foreign content (`build`, `node_modules`, ...) come last among the folders. Hidden entries
 * (a leading dot) and every other kind of file are left out. One folder at a time: the tree lists a sub-folder only
 * when it is opened, so showing a large folder costs nothing until then.
 */
object FolderListing {
    const val MAX_ENTRIES = 500

    /** Folder names listed after the others: build output and dependency folders. */
    val LAST = setOf("build", "node_modules", "target", "out", "dist")

    fun isRequestFile(name: String) = name.endsWith(".http", ignoreCase = true)

    fun list(dir: Path): FolderContents {
        if (!Files.isDirectory(dir)) return FolderContents(emptyList(), error = "the folder does not exist")
        return try { listExisting(dir) } catch (e: Exception) { FolderContents(emptyList(), error = e.message ?: e::class.simpleName) }
    }

    private fun listExisting(dir: Path): FolderContents {
        val all = Files.newDirectoryStream(dir).use { stream ->
            stream.mapNotNull { p ->
                val name = p.fileName?.toString() ?: return@mapNotNull null
                when {
                    name.startsWith(".") -> null
                    Files.isDirectory(p) -> FolderEntry(p, name, true)
                    Files.isRegularFile(p) && isRequestFile(name) -> FolderEntry(p, name, false, realKey(p))
                    else -> null
                }
            }
        }
        val byName = compareBy<FolderEntry> { it.name.lowercase() }.thenBy { it.name }
        val folders = all.filter { it.directory }.sortedWith(compareBy<FolderEntry> { it.name in LAST }.then(byName))
        val files = all.filter { !it.directory }.sortedWith(byName)
        return FolderContents(
            folders.take(MAX_ENTRIES) + files.take(MAX_ENTRIES),
            (folders.size - MAX_ENTRIES).coerceAtLeast(0) + (files.size - MAX_ENTRIES).coerceAtLeast(0),
        )
    }

    /** The real path of [p] as text, or its normalised absolute path when that cannot be resolved. */
    fun realKey(p: Path): String = try { p.toRealPath().toString() } catch (_: Exception) { p.toAbsolutePath().normalize().toString() }
}
