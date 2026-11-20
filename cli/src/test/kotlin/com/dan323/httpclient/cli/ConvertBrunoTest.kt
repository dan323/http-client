package com.dan323.httpclient.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConvertBrunoTest {
    private val dir = Files.createTempDirectory("httpc-convert")
    private val src = Files.createDirectory(dir.resolve("src"))
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    @AfterTest fun cleanup() { dir.toFile().deleteRecursively() }

    private fun run(vararg args: String) = run(args.toList(), PrintStream(out), PrintStream(err))

    @Test fun convertsACollectionAndReportsWhatItSkipped() {
        Files.writeString(src.resolve("bruno.json"), "{}")
        Files.writeString(src.resolve("a.bru"), "meta {\n  name: A\n  seq: 1\n}\n\nget {\n  url: http://h\n}\n\ntests {\n  x\n}\n")
        val target = dir.resolve("out")
        assertEquals(0, run("convert-bruno", src.toString(), "--out", target.toString()))
        assertTrue(Files.isRegularFile(target.resolve("requests.http")))
        assertContains(out.toString(), "requests.http")
        assertContains(err.toString(), "warning: a.bru:")
        assertContains(err.toString(), "1 file written, 1 warning")
    }

    @Test fun anInputThatIsNotACollectionFails() {
        assertEquals(1, run("convert-bruno", src.toString(), "--out", dir.resolve("out").toString()))
        assertContains(err.toString(), "bruno.json")
    }

    @Test fun badUsageExits2() {
        assertEquals(2, run("convert-bruno", src.toString()))
        assertEquals(2, run("convert-bruno", "--out", "x"))
        assertEquals(2, run("convert-bruno", "a", "b", "--out", "x"))
        assertEquals(2, run("convert-bruno", "a", "--out", "x", "--out", "y"))
        assertEquals(2, run("convert-bruno", "a", "--out"))
    }
}
