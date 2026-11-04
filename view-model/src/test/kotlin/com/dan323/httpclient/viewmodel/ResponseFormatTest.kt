package com.dan323.httpclient.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals

class ResponseFormatTest {
    @Test fun statusClasses() {
        assertEquals(StatusClass.SUCCESS, statusClass(204))
        assertEquals(StatusClass.REDIRECT, statusClass(302))
        assertEquals(StatusClass.CLIENT_ERROR, statusClass(404))
        assertEquals(StatusClass.SERVER_ERROR, statusClass(503))
        assertEquals(StatusClass.INFORMATIONAL, statusClass(101))
        assertEquals(StatusClass.UNKNOWN, statusClass(0))
    }

    @Test fun sizes() {
        assertEquals("12 B", sizeLabel(12))
        assertEquals("1.5 KB", sizeLabel(1536))
        assertEquals("2.0 MB", sizeLabel(2 * 1024 * 1024))
    }

    @Test fun durations() {
        assertEquals("42 ms", durationLabel(42))
        assertEquals("1.5 s", durationLabel(1500))
    }

    @Test fun statusTexts() {
        assertEquals("Not Found", statusText(404))
        assertEquals("", statusText(299))
    }
}
