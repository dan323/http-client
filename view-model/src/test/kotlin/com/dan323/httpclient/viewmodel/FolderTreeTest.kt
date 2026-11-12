package com.dan323.httpclient.viewmodel

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FolderTreeTest {
    private val root = Path.of("/ws")
    private val api = root.resolve("api")
    private fun dir(p: Path) = FolderEntry(p, p.fileName.toString(), true)
    private fun file(p: Path) = FolderEntry(p, p.fileName.toString(), false)
    private val rootListing = FolderContents(listOf(dir(api), file(root.resolve("a.http"))))
    private val apiListing = FolderContents(listOf(file(api.resolve("b.http"))))

    private fun tree() = FolderTree(root).withListing(root, rootListing)

    @Test fun showsTheRootAndOpensAListedFolder() {
        assertEquals(listOf("api", "a.http"), tree().rows().map { it.entry!!.name })
        val open = tree().withListing(api, apiListing).toggled(api)
        assertEquals(listOf("api", "b.http", "a.http"), open.rows().map { it.entry!!.name })
        assertEquals(listOf(0, 1, 0), open.rows().map { it.depth })
        assertEquals(listOf(true, false, false), open.rows().map { it.expanded })
        assertEquals(listOf("api", "a.http"), open.toggled(api).rows().map { it.entry!!.name })
    }

    @Test fun aFolderThatWasNotListedStaysClosed() {
        val t = tree().toggled(api)
        assertTrue(!t.isExpanded(api))
        assertTrue(!t.isListed(api))
    }

    @Test fun reloadSwapsAllListingsAndClosesFoldersThatCannotBeReadAnymore() {
        val open = tree().withListing(api, apiListing).toggled(api)
        assertEquals(listOf(root, api), open.foldersToList())
        val fresh = open.reloaded(mapOf(
            root to FolderContents(rootListing.entries + file(root.resolve("c.http"))),
            api to FolderContents(emptyList(), error = "the folder does not exist"),
        ))
        assertTrue(!fresh.isExpanded(api))
        assertEquals(listOf("api", "a.http", "c.http"), fresh.rows().map { it.entry!!.name })
        val kept = open.reloaded(mapOf(root to rootListing, api to apiListing))
        assertTrue(kept.isExpanded(api))
    }

    @Test fun notesAndEntriesHaveUniqueKeys() {
        val other = root.resolve("empty")
        val t = FolderTree(root)
            .withListing(root, FolderContents(listOf(dir(api), dir(other)), hidden = 3))
            .withListing(api, FolderContents(emptyList())).withListing(other, FolderContents(emptyList()))
            .toggled(api).toggled(other)
        val rows = t.rows()
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertTrue(rows.any { it.note == "… and 3 more" })
        assertEquals(2, rows.count { it.note == "(no .http files)" })   // two empty folders: the same text, different keys
    }

    @Test fun anUnreadableRootShowsTheReason() {
        val t = FolderTree(root).withListing(root, FolderContents(emptyList(), error = "denied"))
        assertEquals("Could not read this folder: denied", t.rows().single().note)
    }
}
