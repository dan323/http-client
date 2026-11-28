package com.dan323.httpclient.core

import com.dan323.httpclient.core.port.out.Allowlists
import com.dan323.httpclient.core.port.out.DesktopSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopSettingsTest {
    @Test fun allowlistsAreNeverSetUntilChosenAndAnEmptyChoiceIsKept() {
        assertNull(DesktopSettings().allowlists)
        val granted = DesktopSettings().withAllowlists(Allowlists(listOf("TOKEN"), listOf("/data")))
        assertEquals(Allowlists(listOf("TOKEN"), listOf("/data")), granted.allowlists)
        assertEquals(listOf("TOKEN"), granted.allowlists!!.envNames)
        assertEquals(listOf("/data"), granted.allowlists!!.fileRoots)
        assertEquals(Allowlists(), granted.withAllowlists(Allowlists()).allowlists)
    }

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
