package com.dan323.httpclient.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenDocumentTest {
    private val file = Files.createTempFile("doc", ".http")

    @Test fun parsesTheContentAtOnceAndAgainWhenTheTextChanges() {
        val doc = OpenDocument(file, "GET http://x/a\n")
        assertEquals(1, doc.requests.size)
        assertNull(doc.parseError)
        assertFalse(doc.dirty)
        doc.assignText("GET http://x/a\n\n###\nGET http://x/b\n")
        assertEquals(2, doc.requests.size)
        assertEquals("GET http://x/a\n\n###\nGET http://x/b\n", doc.text)
    }

    @Test fun aFileThatDoesNotParseListsNothingAndKeepsTheError() {
        val doc = OpenDocument(file, "")
        doc.assignText("GET\n")
        assertTrue(doc.requests.isEmpty())
        assertTrue(doc.parseError != null)
    }

    @Test fun reopeningAFileStartsWithoutTheEarlierChoicesAndResults() {
        AppState().use { app ->
            Files.writeString(file, "GET http://x/a\n\n###\nGET http://x/b\n")
            app.open(file)
            app.selected = 1
            app.edit("GET http://x/c\n")
            assertTrue(app.dirty)
            app.open(file)
            assertEquals(0, app.selected)
            assertFalse(app.dirty)
            assertEquals(2, app.requests.size)
            assertTrue(app.outcomes.isEmpty())
        }
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
