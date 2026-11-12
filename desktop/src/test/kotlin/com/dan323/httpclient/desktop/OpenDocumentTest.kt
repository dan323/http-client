package com.dan323.httpclient.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenDocumentTest {
    private val file = Files.createTempFile("doc", ".http").also { it.toFile().deleteOnExit() }

    @Test fun parsesTheContentAtOnceAndAgainWhenTheTextChanges() {
        val doc = OpenDocument(file, "GET http://x/a\n")
        assertEquals(1, doc.requests.size)
        assertNull(doc.parseError)
        assertFalse(doc.dirty)
        doc.edit("GET http://x/a\n\n###\nGET http://x/b\n")
        assertEquals(2, doc.requests.size)
        assertEquals("GET http://x/a\n\n###\nGET http://x/b\n", doc.text)
        assertTrue(doc.dirty)
    }

    @Test fun aFileThatDoesNotParseListsNothingAndKeepsTheError() {
        val doc = OpenDocument(file, "")
        doc.edit("GET\n")
        assertTrue(doc.requests.isEmpty())
        assertTrue(doc.parseError != null)
    }

    @Test fun reopeningAFileStartsWithoutTheEarlierChoicesAndResults() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> val b = "ok".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        server.start()
        try {
            val dir = Files.createTempDirectory("httpc-doc")
            Files.writeString(dir.resolve("http-client.env.json"), """{"dev":{"base":"http://127.0.0.1:${server.address.port}"}}""")
            val f = dir.resolve("a.http")
            Files.writeString(f, "GET {{base}}/a\n\n###\nGET {{base}}/b\n")
            AppState().use { app ->
                app.open(f)
                app.selected = 1
                app.environment = "dev"
                app.run(false)
                assertEquals(1, app.outcomes.size)
                app.edit("GET {{base}}/c\n")
                assertTrue(app.dirty)

                app.open(f)
                assertEquals(0, app.selected)
                assertNull(app.environment)          // the choice belongs to the earlier document (nothing is remembered without settings)
                assertEquals(listOf("dev"), app.environments)
                assertTrue(app.outcomes.isEmpty())
                assertFalse(app.cancelled)
                assertFalse(app.dirty)
                assertEquals(2, app.requests.size)
            }
        } finally { server.stop(0) }
    }

    @Test fun aSaveClearsTheDirtyFlagOnlyForTheTextThatWasWritten() {
        val doc = OpenDocument(file, "GET http://x/a\n")
        doc.edit("GET http://x/b\n")
        assertTrue(doc.dirty)
        doc.saved("GET http://x/a\n")   // an older snapshot reached the disk: the buffer has moved on
        assertTrue(doc.dirty)
        doc.saved("GET http://x/b\n")
        assertFalse(doc.dirty)
    }

    @Test fun beginRunClearsResultsAndTheCancelledFlag() {
        val doc = OpenDocument(file, "GET http://x/a\n")
        val outcome = com.dan323.httpclient.core.application.RunOutcome.Failure(doc.requests.single(), "boom")
        doc.addOutcome(outcome); doc.markCancelled()
        assertEquals(listOf<com.dan323.httpclient.core.application.RunOutcome>(outcome), doc.outcomes)
        assertTrue(doc.cancelled)
        doc.beginRun()
        assertTrue(doc.outcomes.isEmpty())
        assertFalse(doc.cancelled)
    }

    @Test fun withoutAFileEverythingIsEmptyAndEditsAreIgnored() {
        AppState().use { app ->
            assertNull(app.file)
            assertEquals("", app.text)
            assertEquals(0, app.selected)
            app.edit("GET http://x\n")
            assertEquals("", app.text)
            assertFalse(app.dirty)
        }
    }
}
