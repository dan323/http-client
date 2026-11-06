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
        SessionFactory().use { factory ->
            val out = factory.create(file, FileAccessPolicy()).runAll(HttpFileParser().parse(Files.readString(file)).requests, emptyMap())
            assertIs<RunOutcome.Failure>(out.single())
        }
    }

    @Test fun badConfigurationComesBackAsFailure() {
        val r = FileAccessPolicy.load({ "relative" }, { null })
        assertTrue(r.isFailure)
    }
}
