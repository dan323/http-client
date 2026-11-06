package com.dan323.httpclient.adapters

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.PosixFilePermission

class AtomicReportWriterTest {
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

    private val posix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    @Test fun createsParentsAndLeavesNoTempFile() {
        val dir = createTempDirectory("writer")
        val target = dir.resolve("a/b/report.json")
        AtomicReportWriter().write(target, "x")
        assertEquals("x", target.readText())
        Files.list(target.parent).use { assertEquals(listOf("report.json"), it.map { p -> p.fileName.toString() }.toList()) }
    }

    @Test fun newReportIsOwnerOnly() {
        if (!posix) return
        val dir = createTempDirectory("writer")
        val target = dir.resolve("report.json")
        AtomicReportWriter().write(target, "x")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
    }

    @Test fun replacingKeepsExistingPermissions() {
        if (!posix) return
        val dir = createTempDirectory("writer")
        val target = dir.resolve("report.json").also { it.writeText("old") }
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r-----"))
        AtomicReportWriter().write(target, "new")
        assertEquals("new", target.readText())
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
    }

    @Test fun unwritableTargetThrowsIOException() {
        val dir = createTempDirectory("writer")
        val blocker = dir.resolve("blocker").also { it.writeText("x") }
        var thrown = false
        try { AtomicReportWriter().write(blocker.resolve("r.json"), "x") } catch (_: java.io.IOException) { thrown = true }
        assertTrue(thrown)
    }
}
