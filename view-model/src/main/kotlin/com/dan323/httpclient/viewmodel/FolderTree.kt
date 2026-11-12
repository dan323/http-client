package com.dan323.httpclient.viewmodel

import java.nio.file.Path

/** One visible line of the tree: an [entry] at [depth] (with whether a folder is [expanded]) or, without an entry, a [note]. [key] is unique in one tree. */
class FolderRow(val key: String, val entry: FolderEntry?, val depth: Int, val expanded: Boolean, val note: String? = null)

/**
 * The state of a folder tree as plain data: the folders that are open and what each one held when it was listed.
 * Immutable: a view keeps the current value and replaces it after each change, and does the listing itself (off the
 * UI thread). A folder is shown open only when it was listed.
 */
class FolderTree(
    val root: Path,
    private val expanded: Set<Path> = emptySet(),
    private val contents: Map<Path, FolderContents> = emptyMap(),
) {
    fun isExpanded(dir: Path) = dir in expanded
    fun isListed(dir: Path) = dir in contents

    /** [dir] listed as [listed]. (A `Path` is itself an `Iterable<Path>`: sets and lists are combined with sets and lists, never with a bare path.) */
    fun withListing(dir: Path, listed: FolderContents) = FolderTree(root, expanded, contents + (dir to listed))

    /** Opens [dir] (it must have been listed) or closes it. */
    fun toggled(dir: Path): FolderTree = when {
        dir in expanded -> FolderTree(root, expanded - setOf(dir), contents)
        dir in contents -> FolderTree(root, expanded + setOf(dir), contents)
        else -> this
    }

    /** The folders whose listing a reload has to ask for again: the root and every open folder. */
    fun foldersToList(): List<Path> = listOf(root) + expanded.filter { it != root }.toList()

    /** Replaces every listing with [fresh] at once; an open folder that can no longer be read (deleted, say) is closed. */
    fun reloaded(fresh: Map<Path, FolderContents>) =
        FolderTree(root, expanded.filter { fresh[it]?.error == null && it in fresh }.toSet(), fresh)

    /** The visible lines, depth first: the root's entries and, below each open folder, its own. */
    fun rows(): List<FolderRow> {
        val out = mutableListOf<FolderRow>()
        fun add(dir: Path, depth: Int) {
            val c = contents[dir] ?: return
            c.error?.let { out += FolderRow("note:error:$dir", null, depth, false, "Could not read this folder: $it") }
            if (c.entries.isEmpty() && c.error == null) out += FolderRow("note:empty:$dir", null, depth, false, "(no .http files)")
            for (e in c.entries) {
                val open = e.directory && e.path in expanded
                out += FolderRow(e.path.toString(), e, depth, open)
                if (open) add(e.path, depth + 1)
            }
            if (c.hidden > 0) out += FolderRow("note:more:$dir", null, depth, false, "… and ${c.hidden} more")
        }
        add(root, 0)
        return out
    }
}
