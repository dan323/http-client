package com.dan323.httpclient.viewmodel

import java.util.Locale
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
        assertEquals(StatusClass.UNKNOWN, statusClass(-1))
        assertEquals(StatusClass.UNKNOWN, statusClass(99))
        assertEquals(StatusClass.INFORMATIONAL, statusClass(100))
        assertEquals(StatusClass.INFORMATIONAL, statusClass(199))
        assertEquals(StatusClass.SUCCESS, statusClass(200))
        assertEquals(StatusClass.REDIRECT, statusClass(399))
        assertEquals(StatusClass.CLIENT_ERROR, statusClass(400))
        assertEquals(StatusClass.CLIENT_ERROR, statusClass(499))
        assertEquals(StatusClass.SERVER_ERROR, statusClass(500))
        assertEquals(StatusClass.SERVER_ERROR, statusClass(599))
        assertEquals(StatusClass.UNKNOWN, statusClass(600))
    }

    @Test fun sizes() {
        assertEquals("12 B", sizeLabel(12))
        assertEquals("1.5 KB", sizeLabel(1536))
        assertEquals("2.0 MB", sizeLabel(2 * 1024 * 1024))
    }

    @Test fun sizeBoundaries() {
        assertEquals("0 B", sizeLabel(0))
        assertEquals("1023 B", sizeLabel(1023))
        assertEquals("1.0 KB", sizeLabel(1024))
        assertEquals("1.0 MB", sizeLabel(1048575))
        assertEquals("1.0 MB", sizeLabel(1048576))
        assertEquals("1023.9 KB", sizeLabel(1048500))
        assertEquals("1.0 GB", sizeLabel(1073741824 - 1))
        assertEquals("2.0 GB", sizeLabel(Int.MAX_VALUE))
        assertEquals("0 B", sizeLabel(-1))
        assertEquals("0 B", sizeLabel(Int.MIN_VALUE))
    }

    @Test fun durationBoundaries() {
        assertEquals("0 ms", durationLabel(0))
        assertEquals("999 ms", durationLabel(999))
        assertEquals("1.0 s", durationLabel(1000))
        assertEquals("2.0 s", durationLabel(1999))
        assertEquals("59.9 s", durationLabel(59_949))
        assertEquals("1m 0s", durationLabel(59_999))
        assertEquals("1m 0s", durationLabel(60_000))
        assertEquals("2m 5s", durationLabel(125_000))
        assertEquals("0 ms", durationLabel(-1))
    }

    @Test fun labelsIgnoreDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.5 KB", sizeLabel(1536))
            assertEquals("1.5 s", durationLabel(1500))
        } finally {
            Locale.setDefault(saved)
        }
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
