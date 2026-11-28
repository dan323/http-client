package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.RunOutcome
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionFactoryTest {
    @Test fun assemblesASessionThatReadsConfinedFilesAndStaysUsableUntilClosed() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val b = ex.requestBody.readAllBytes(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            val dir = Files.createTempDirectory("factory")
            Files.writeString(dir.resolve("body.txt"), "from-file")
            val file = dir.resolve("a.http").also { Files.writeString(it, "POST http://127.0.0.1:${server.address.port}/\n\n< ./body.txt\n") }
            SessionFactory().use { factory ->
                val session = factory.create(file, FileAccessPolicy())
                val out = session.runAll(HttpFileParser().parse(Files.readString(file)).requests, emptyMap())
                assertEquals("from-file", (out.single() as RunOutcome.Success).response.bodyAsString())
            }
        } finally { server.stop(0) }
    }

    @Test fun fileOutsideTheFolderIsRefused() {
        val dir = Files.createTempDirectory("factory")
        val outside = Files.createTempFile("outside", ".txt")
        val file = dir.resolve("a.http").also { Files.writeString(it, "POST http://127.0.0.1:1/\n\n< $outside\n") }
        var sent = false
        val executor = com.dan323.httpclient.core.port.out.HttpExecutor {
            sent = true
            com.dan323.httpclient.core.domain.HttpResponse(200, emptyList(), ByteArray(0), 0)
        }
        SessionFactory(executor).use { factory ->
            val out = factory.create(file, FileAccessPolicy()).runAll(HttpFileParser().parse(Files.readString(file)).requests, emptyMap())
            val failure = assertIs<RunOutcome.Failure>(out.single())
            assertTrue("is outside the allowed folders" in failure.message)
            assertTrue(!sent)
        }
    }

    @Test fun createConfiguredAddsTheSettingsAllowlistsAndHints() {
        val outside = Files.createTempDirectory("granted")
        Files.writeString(outside.resolve("body.txt"), "granted-body")
        val other = Files.createTempFile("other", ".txt")
        val dir = Files.createTempDirectory("factory")
        val file = dir.resolve("a.http")
        val text = "POST http://h/\nX-Path: {{\$env.PATH}}\n\n< ${outside.resolve("body.txt")}\n\n###\nPOST http://h/\n\n< $other\n\n###\nGET http://h/{{\$env.NOT_GRANTED_X}}\n"
        val sent = mutableListOf<com.dan323.httpclient.core.domain.HttpRequest>()
        val executor = com.dan323.httpclient.core.port.out.HttpExecutor {
            sent += it; com.dan323.httpclient.core.domain.HttpResponse(200, emptyList(), ByteArray(0), 0)
        }
        withoutAmbientRoots {
            SessionFactory(executor).use { f ->
                val granted = com.dan323.httpclient.core.port.out.Allowlists(listOf(" PATH ", ""), listOf(outside.toString(), " "))
                val session = f.createConfigured(file, granted, fileHint = "FILE-HINT", envHint = "ENV-HINT").getOrThrow()
                val out = session.runAll(HttpFileParser().parse(text).requests, emptyMap())
                assertIs<RunOutcome.Success>(out[0])
                assertEquals("granted-body", String(sent.single().body!!))
                assertTrue(sent.single().headers.single { it.name == "X-Path" }.value.isNotEmpty())   // PATH was read
                assertTrue("FILE-HINT" in assertIs<RunOutcome.Failure>(out[1]).message)
                assertTrue("ENV-HINT" in assertIs<RunOutcome.Failure>(out[2]).message)
                val bad = f.createConfigured(file, com.dan323.httpclient.core.port.out.Allowlists(fileRoots = listOf("relative/dir")))
                assertTrue(bad.exceptionOrNull()!!.message!!.contains("'relative/dir' must be an absolute path"))
            }
        }
    }

    /** Runs [block] with the file-root system property set to nothing, so a value on the test machine cannot leak in. */
    private fun withoutAmbientRoots(block: () -> Unit) {
        val property = FileAccessPolicy.ALLOW_PROPERTY
        val previous = System.getProperty(property)
        try { System.setProperty(property, ""); block() } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @Test fun badConfigurationComesBackAsFailure() {
        val r = FileAccessPolicy.load({ "relative" }, { null })
        assertTrue(r.isFailure)
    }
}

class SessionFactoryOwnershipTest {
    private class CountingExecutor : com.dan323.httpclient.core.port.out.HttpExecutor, AutoCloseable {
        var closed = 0
        val sent = mutableListOf<String>()
        override fun execute(request: com.dan323.httpclient.core.domain.HttpRequest): com.dan323.httpclient.core.domain.HttpResponse {
            sent += request.url
            return com.dan323.httpclient.core.domain.HttpResponse(200, emptyList(), ByteArray(0), 1)
        }
        override fun close() { closed++ }
    }

    private fun run(session: com.dan323.httpclient.core.application.RequestSession) =
        session.runAll(HttpFileParser().parse("GET http://h/x\n").requests, emptyMap())

    @Test fun sessionsShareTheFactoryExecutorAndCloseReleasesItOnce() {
        val exec = CountingExecutor()
        val file = Files.createTempDirectory("factory").resolve("a.http")
        val factory = SessionFactory(exec)
        run(factory.create(file, FileAccessPolicy()))
        run(factory.create(file, FileAccessPolicy()))
        assertEquals(2, exec.sent.size)
        assertEquals(0, exec.closed)
        factory.close()
        assertEquals(1, exec.closed)
    }

    @Test fun sessionsAreIndependentAndEmpty() {
        val file = Files.createTempDirectory("factory").resolve("a.http")
        SessionFactory(CountingExecutor()).use { f ->
            val a = f.create(file, FileAccessPolicy())
            val b = f.create(file, FileAccessPolicy())
            assertTrue(a !== b)
            assertTrue(!a.hasResponse("login"))
        }
    }

    @Test fun createConfiguredBuildsASessionWhenSettingsAreValid() {
        val file = Files.createTempDirectory("factory").resolve("a.http")
        val property = FileAccessPolicy.ALLOW_PROPERTY
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, "")
            SessionFactory(CountingExecutor()).use { f -> assertTrue(f.createConfigured(file).isSuccess) }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @Test fun closingADefaultFactoryTwiceIsHarmless() {
        val f = SessionFactory()
        f.close(); f.close()
    }
}
