package com.dan323.httpclient.cli

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** `--output` writes the response bytes exactly as received (G3): binary and non-UTF-8 text alike. */
class ByteExactOutputTest {
    private val dir = Files.createTempDirectory("httpc-bytes")
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @AfterTest fun tearDown() { server.stop(0); dir.toFile().deleteRecursively() }

    private fun serve(path: String, contentType: String?, body: ByteArray, status: Int = 200) {
        server.createContext(path) { ex ->
            if (contentType != null) ex.responseHeaders.add("Content-Type", contentType)
            ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
            ex.close()
        }
        server.start()
    }

    private fun download(path: String, target: java.nio.file.Path): Pair<Int, String> {
        val f = dir.resolve("r.http").also { Files.writeString(it, "GET $base$path\n") }
        val e = ByteArrayOutputStream()
        val code = run(listOf(f.toString(), "--quiet", "--output", target.toString()), PrintStream(ByteArrayOutputStream()), PrintStream(e))
        return code to e.toString()
    }

    @Test fun binaryBodyWithAll256ByteValuesIsWrittenByteExact() {
        val body = ByteArray(256 * 4) { (it % 256).toByte() }
        serve("/bin", "application/octet-stream", body)
        val target = dir.resolve("sub/file.bin")
        val (code, err) = download("/bin", target)
        assertEquals(0, code, err)
        assertContentEquals(body, Files.readAllBytes(target))
    }

    @Test fun nonUtf8TextIsWrittenUnchangedNotTranscoded() {
        val body = "café über £".toByteArray(Charsets.ISO_8859_1)
        serve("/latin", "text/plain; charset=ISO-8859-1", body)
        val target = dir.resolve("latin.txt")
        assertEquals(0, download("/latin", target).first)
        assertContentEquals(body, Files.readAllBytes(target))
    }

    @Test fun emptyBodyGivesAnEmptyFile() {
        serve("/empty", null, ByteArray(0), 204)
        val target = dir.resolve("empty.bin").also { Files.writeString(it, "old content") }
        assertEquals(0, download("/empty", target).first)
        assertEquals(0L, Files.size(target))
    }

    @Test fun nothingIsWrittenWhenTheRequestFails() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val f = dir.resolve("r.http").also { Files.writeString(it, "GET http://127.0.0.1:$port/x\n") }
        val target = dir.resolve("never.bin")
        assertEquals(1, run(listOf(f.toString(), "--output", target.toString()), PrintStream(ByteArrayOutputStream()), PrintStream(ByteArrayOutputStream())))
        assertFalse(Files.exists(target))
    }

    @Test fun binaryOutputIsOwnerOnlyOnPosix() {
        assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        serve("/bin", "image/png", byteArrayOf(0, -1, 127, -128))
        val target = dir.resolve("img.png")
        assertEquals(0, download("/bin", target).first)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
    }

    @Test fun gzipBodyIsWrittenAsTheCompressedBytesReceived() {
        val plain = "hello hello hello hello".toByteArray()
        val gz = ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(plain) } }.toByteArray()
        server.createContext("/gz") { ex ->
            ex.responseHeaders.add("Content-Encoding", "gzip")
            ex.sendResponseHeaders(200, gz.size.toLong())
            ex.responseBody.use { it.write(gz) }
            ex.close()
        }
        server.start()
        val target = dir.resolve("gz.bin")
        assertEquals(0, download("/gz", target).first)
        assertContentEquals(gz, Files.readAllBytes(target)) // not decompressed
    }

    @Test fun outputNamingASavedResponseIsRefusedAndNeverWritten() {
        serve("/saved", "text/plain", "response".toByteArray())
        val f = dir.resolve("s.http").also { Files.writeString(it, "GET $base/saved\n>> saved.txt\n") }
        val calls = mutableListOf<java.nio.file.Path>()
        val e = ByteArrayOutputStream()
        val code = run(
            listOf(f.toString(), "--quiet", "--output", dir.resolve("sub/../saved.txt").toString()),
            PrintStream(ByteArrayOutputStream()), PrintStream(e), outputWriter = com.dan323.httpclient.core.port.out.OutputWriter { p, _ -> calls.add(p) },
        )
        val err = e.toString()
        assertEquals(3, code, err)
        assertContains(err, "Cannot write output")
        assertContains(err, "saved by '>>'")
        assertContains(err, "Run passed; only the report/output file could not be written.")
        assertEquals(emptyList(), calls) // the writer was never reached
        assertEquals("response", Files.readString(dir.resolve("saved.txt")))
    }

    @Test fun unwritableOutputGivesExit3OnAPassedRun() {
        serve("/ok", "text/plain", "x".toByteArray())
        val f = dir.resolve("u.http").also { Files.writeString(it, "GET $base/ok\n") }
        val e = ByteArrayOutputStream()
        val code = run(
            listOf(f.toString(), "--quiet", "--output", dir.resolve("o.bin").toString()),
            PrintStream(ByteArrayOutputStream()), PrintStream(e), outputWriter = com.dan323.httpclient.core.port.out.OutputWriter { _, _ -> throw java.io.IOException("disk full") },
        )
        val err = e.toString()
        assertEquals(3, code, err)
        assertContains(err, "Error: Cannot write output")
        assertContains(err, "disk full")
        assertContains(err, "Run passed; only the report/output file could not be written.")
    }

    @Test fun outputPathThatIsADirectoryGivesExit3() {
        serve("/ok", "text/plain", "x".toByteArray())
        val target = Files.createDirectory(dir.resolve("adir"))
        val (code, err) = download("/ok", target)
        assertEquals(3, code, err)
        assertContains(err, "Cannot write output")
    }
}
