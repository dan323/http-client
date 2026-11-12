package com.dan323.httpclient.viewmodel

import java.nio.file.Files
import java.nio.file.Path

/** One line of a folder view: a sub-folder or a `.http` file. */
class FolderEntry(val path: Path, val name: String, val directory: Boolean)

/** What one folder holds for the view: [entries] (at most [FolderListing.MAX_ENTRIES]), how many more there were, and why it could not be read. */
class FolderContents(val entries: List<FolderEntry>, val hidden: Int = 0, val error: String? = null)

/**
 * Lists a folder for a file tree: sub-folders first, then `.http` files, each sorted by name ignoring case. Hidden entries
 * (a leading dot), folders that only hold generated or foreign content (`build`, `node_modules`, ...) and every other
 * kind of file are left out. One folder at a time: the tree lists a sub-folder only when it is opened.
 */
object FolderListing {
    const val MAX_ENTRIES = 500

    /** Folder names never listed: build output and dependency folders that can hold thousands of entries and no requests. */
    val SKIPPED = setOf("build", "node_modules", "target", "out", "dist")

    fun isRequestFile(name: String) = name.endsWith(".http", ignoreCase = true)

    fun list(dir: Path): FolderContents = try {
        val all = Files.newDirectoryStream(dir).use { stream ->
            stream.mapNotNull { p ->
                val name = p.fileName?.toString() ?: return@mapNotNull null
                when {
                    name.startsWith(".") -> null
                    Files.isDirectory(p) -> if (name in SKIPPED) null else FolderEntry(p, name, true)
                    Files.isRegularFile(p) && isRequestFile(name) -> FolderEntry(p, name, false)
                    else -> null
                }
            }
        }.sortedWith(compareByDescending<FolderEntry> { it.directory }.thenBy { it.name.lowercase() }.thenBy { it.name })
        FolderContents(all.take(MAX_ENTRIES), (all.size - MAX_ENTRIES).coerceAtLeast(0))
    } catch (e: Exception) {
        FolderContents(emptyList(), error = e.message ?: e::class.simpleName)
    }
}
