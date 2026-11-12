package com.dan323.httpclient.core

import com.dan323.httpclient.core.port.out.DesktopSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopSettingsTest {
    @Test fun openFilesAreKeptInOrderWithoutDuplicatesAndBounded() {
        val s = DesktopSettings().withOpenFiles(listOf("a", "b", "a"), "b")
        assertEquals(listOf("a", "b"), s.openFiles)
        assertEquals("b", s.activeFile)
        val many = (1..25).map { "f$it" }
        assertEquals(DesktopSettings.MAX_OPEN_FILES, DesktopSettings().withOpenFiles(many, null).openFiles!!.size)
    }

    @Test fun theActiveFileMustBeOneOfTheOpenOnes() {
        assertNull(DesktopSettings().withOpenFiles(listOf("a"), "z").activeFile)
        assertNull(DesktopSettings().withOpenFiles(emptyList(), "a").activeFile)
    }

    @Test fun settingsWithoutTabsAreNotKnownToHaveNone() {
        assertNull(DesktopSettings().openFiles)
        assertEquals(emptyList(), DesktopSettings().withOpenFiles(emptyList(), null).openFiles)
    }

    @Test fun removingARecentLeavesTheOpenTabsAlone() {
        val s = DesktopSettings(lastFile = "a", recentFiles = listOf("a", "b")).withOpenFiles(listOf("a", "b"), "a").withoutRecent("a")
        assertEquals(listOf("b"), s.recentFiles)
        assertNull(s.lastFile)
        assertEquals(listOf("a", "b"), s.openFiles)   // the tabs are a separate fact; the app updates them when they change
        assertEquals("a", s.activeFile)
        assertNull(DesktopSettings().withoutRecent("a").openFiles)
    }
}
