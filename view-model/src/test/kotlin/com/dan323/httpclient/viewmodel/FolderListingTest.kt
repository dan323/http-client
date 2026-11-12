package com.dan323.httpclient.viewmodel

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FolderListingTest {
    private val root: Path = Files.createTempDirectory("httpc-folder")
    private fun file(name: String): Path = root.resolve(name).also { it.parent?.let(Files::createDirectories); Files.writeString(it, "GET http://x\n") }
    private fun dir(name: String): Path = Files.createDirectories(root.resolve(name))

    @Test fun listsFoldersFirstThenHttpFilesSortedIgnoringCase() {
        dir("zeta"); dir("Alpha"); file("b.http"); file("A.HTTP"); file("notes.txt"); file("c.http")
        val e = FolderListing.list(root).entries
        assertEquals(listOf("Alpha", "zeta", "A.HTTP", "b.http", "c.http"), e.map { it.name })
        assertEquals(listOf(true, true, false, false, false), e.map { it.directory })
    }

    @Test fun leavesOutHiddenAndGeneratedFolders() {
        dir(".git"); dir("build"); dir("node_modules"); dir("api"); file(".hidden.http"); file("ok.http")
        assertEquals(listOf("api", "ok.http"), FolderListing.list(root).entries.map { it.name })
    }

    @Test fun listsOnlyTheFolderItselfNotItsChildren() {
        file("api/deep/x.http")
        val api = FolderListing.list(root).entries.single()
        assertEquals("api", api.name)
        assertEquals(listOf("deep"), FolderListing.list(api.path).entries.map { it.name })
    }

    @Test fun cutsAVeryLargeFolderAndSaysHowManyWereLeftOut() {
        repeat(FolderListing.MAX_ENTRIES + 7) { Files.writeString(root.resolve("f$it.http"), "GET http://x\n") }
        val c = FolderListing.list(root)
        assertEquals(FolderListing.MAX_ENTRIES, c.entries.size)
        assertEquals(7, c.hidden)
    }

    @Test fun aFolderThatCannotBeReadHasAnErrorAndNoEntries() {
        val c = FolderListing.list(root.resolve("missing"))
        assertTrue(c.entries.isEmpty())
        assertNotNull(c.error)
    }

    @Test fun anEmptyFolderIsEmptyWithoutAnError() {
        val c = FolderListing.list(root)
        assertTrue(c.entries.isEmpty())
        assertEquals(0, c.hidden)
        assertEquals(null, c.error)
    }
}
