package com.dan323.httpclient.cli

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class MoveIntoPlaceTest {
    @Test fun replacesExistingTarget() {
        val dir = createTempDirectory("move")
        val tmp = dir.resolve("a.tmp").also { it.writeText("new") }
        val target = dir.resolve("report.json").also { it.writeText("old") }
        moveIntoPlace(tmp, target)
        assertEquals("new", target.readText())
        assertEquals(false, Files.exists(tmp))
    }

    @Test fun fallsBackToPlainMoveWhenAtomicMoveIsUnsupported() {
        val dir = createTempDirectory("move")
        val tmp = dir.resolve("a.tmp").also { it.writeText("new") }
        val target = dir.resolve("report.json").also { it.writeText("old") }
        val attempts = mutableListOf<List<StandardCopyOption>>()
        moveIntoPlace(tmp, target) { a, b, opts ->
            attempts += opts.toList()
            if (StandardCopyOption.ATOMIC_MOVE in opts) throw AtomicMoveNotSupportedException(a.toString(), b.toString(), "test")
            Files.move(a, b, *opts)
        }
        assertEquals(listOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING), attempts[0])
        assertEquals(listOf(StandardCopyOption.REPLACE_EXISTING), attempts[1])
        assertEquals("new", target.readText())
    }
}
