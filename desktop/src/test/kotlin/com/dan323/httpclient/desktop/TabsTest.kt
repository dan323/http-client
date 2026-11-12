package com.dan323.httpclient.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabsTest {
    private val dir = Files.createTempDirectory("httpc-tabs")
    private fun file(name: String, text: String = "GET http://127.0.0.1:1/$name\n"): Path = dir.resolve(name).also { Files.writeString(it, text) }

    @Test fun openingFilesAddsTabsAndTheLastOneIsActive() {
        AppState().use { app ->
            app.open(file("a.http")); app.open(file("b.http"))
            assertEquals(listOf("a.http", "b.http"), app.tabs.map { it.label })
            assertEquals(1, app.activeTab)
            assertEquals("b.http", app.file!!.fileName.toString())
        }
    }

    @Test fun openingAnOpenFileSwitchesToItAndKeepsItsEditsAndSelection() {
        AppState().use { app ->
            val a = file("a.http", "GET http://x/1\n\n###\nGET http://x/2\n")
            app.open(a); app.selected = 1; app.edit("GET http://x/1\n\n###\nGET http://x/2\n\n###\nGET http://x/3\n")
            app.open(file("b.http"))
            app.open(a)
            assertEquals(2, app.tabs.size)
            assertEquals(0, app.activeTab)
            assertEquals(1, app.selected)
            assertTrue(app.dirty)
            assertEquals(3, app.requests.size)
        }
    }

    @Test fun eachTabKeepsItsOwnTextAndSelection() {
        AppState().use { app ->
            app.open(file("a.http", "GET http://x/a\n")); app.open(file("b.http", "GET http://x/b\n"))
            app.edit("GET http://x/b2\n")
            app.switchTo(0)
            assertEquals("GET http://x/a\n", app.text)
            assertFalse(app.dirty)
            app.switchTo(1)
            assertEquals("GET http://x/b2\n", app.text)
            assertTrue(app.tabs[1].dirty)
            assertFalse(app.tabs[0].dirty)
        }
    }

    @Test fun closingACleanTabClosesItAndKeepsTheRightOneActive() {
        AppState().use { app ->
            app.open(file("a.http")); app.open(file("b.http")); app.open(file("c.http"))
            app.switchTo(1)
            app.closeTab(0)                       // before the active tab: the active one stays the same file
            assertEquals(listOf("b.http", "c.http"), app.tabs.map { it.label })
            assertEquals("b.http", app.file!!.fileName.toString())
            app.closeTab(0)                       // the active tab: the next one takes over
            assertEquals("c.http", app.file!!.fileName.toString())
            app.closeTab(0)
            assertNull(app.file)
            assertTrue(app.tabs.isEmpty())
            assertEquals("", app.text)
        }
    }

    @Test fun closingTheLastTabOfSeveralActivatesThePreviousOne() {
        AppState().use { app ->
            app.open(file("a.http")); app.open(file("b.http"))
            app.closeTab(1)
            assertEquals("a.http", app.file!!.fileName.toString())
        }
    }

    @Test fun closingATabWithEditsAsksFirst() {
        AppState().use { app ->
            val a = file("a.http"); app.open(a); app.edit("GET http://x/edited\n")
            app.closeTab(0)
            assertEquals("a.http", assertNotNull(app.closePrompt).label)
            assertEquals(1, app.tabs.size)
            app.cancelClose()
            assertNull(app.closePrompt)
            assertEquals(1, app.tabs.size)
            app.closeTab(0); app.confirmClose(save = false)
            assertTrue(app.tabs.isEmpty())
            assertEquals("GET http://127.0.0.1:1/a.http\n", Files.readString(a))   // discarded: the file is untouched
        }
    }

    @Test fun saveAndCloseWritesTheFileFirst() {
        AppState().use { app ->
            val a = file("a.http"); app.open(a); app.edit("GET http://x/saved\n")
            app.closeTab(0); app.confirmClose(save = true)
            assertTrue(app.tabs.isEmpty())
            assertEquals("GET http://x/saved\n", Files.readString(a))
        }
    }

    @Test fun aFailedSaveKeepsTheTabAndThePrompt() {
        AppState().use { app ->
            val a = file("a.http"); app.open(a); app.edit("GET http://x/saved\n")
            Files.delete(a); Files.createDirectory(a)   // the path is now a directory: writing fails
            app.closeTab(0); app.confirmClose(save = true)
            assertEquals(1, app.tabs.size)
            assertNotNull(app.closePrompt)
            assertNotNull(app.message)
        }
    }

    @Test fun closingTheTabWhoseRequestRunsIsRefusedButAnotherTabMayBeSwitchedTo() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> entered.countDown(); gate.await(10, java.util.concurrent.TimeUnit.SECONDS); val b = "ok".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        server.start()
        try {
            AppState().use { app ->
                app.open(file("a.http", "GET http://127.0.0.1:${server.address.port}/\n"))
                app.open(file("b.http"))
                app.switchTo(0)
                val t = Thread { app.run(all = false) }.also { it.start() }
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(app.runningHere)
                app.closeTab(0)
                assertEquals(2, app.tabs.size)
                assertTrue(app.message!!.contains("while its request is running"))
                app.switchTo(1)
                assertFalse(app.runningHere)       // the other tab does not show "Sending…"
                assertTrue(app.outcomes.isEmpty())
                gate.countDown(); t.join(10_000)
                app.switchTo(0)
                assertEquals(1, app.outcomes.size)  // the results landed on the tab that ran
                app.closeTab(0)
                assertEquals(1, app.tabs.size)
            }
        } finally { gate.countDown(); server.stop(0) }
    }

    @Test fun switchingToAMissingTabDoesNothing() {
        AppState().use { app ->
            app.open(file("a.http"))
            app.switchTo(5); app.switchTo(-1); app.closeTab(9)
            assertEquals(0, app.activeTab)
            assertEquals(1, app.tabs.size)
        }
    }

    @Test fun aCloseQuestionIsDroppedWhenARunStartsAndTheRunningTabCannotBeDiscarded() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> entered.countDown(); gate.await(10, java.util.concurrent.TimeUnit.SECONDS); val b = "ok".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        server.start()
        try {
            AppState().use { app ->
                app.open(file("a.http", "GET http://127.0.0.1:${server.address.port}/\n"))
                app.edit("GET http://127.0.0.1:${server.address.port}/edited\n")
                app.closeTab(0)
                assertNotNull(app.closePrompt)
                val t = Thread { app.run(all = false) }.also { it.start() }
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                assertNull(app.closePrompt)                  // the run saved the file: nothing left to ask
                app.confirmClose(save = false)               // a stale answer does nothing
                assertEquals(1, app.tabs.size)
                gate.countDown(); t.join(10_000)
            }
        } finally { gate.countDown(); server.stop(0) }
    }

    @Test fun tabsWithTheSameNameShowTheirFolders() {
        val p = { d: String, n: String -> Files.createDirectories(dir.resolve(d)).resolve(n).also { Files.writeString(it, "GET http://x\n") } }
        assertEquals(listOf("dev/api.http", "prod/api.http", "other.http"), tabLabels(listOf(p("dev", "api.http"), p("prod", "api.http"), p("dev", "other.http"))))
        assertEquals(listOf("a.http"), tabLabels(listOf(dir.resolve("a.http"))))
        val x = Path.of("/one/dev/api.http"); val y = Path.of("/two/dev/api.http")
        assertEquals(listOf("one/dev/api.http", "two/dev/api.http"), tabLabels(listOf(x, y)))
        AppState().use { app ->
            app.open(p("dev", "api.http")); app.open(p("prod", "api.http"))
            assertEquals(listOf("dev/api.http", "prod/api.http"), app.tabs.map { it.label })
            app.edit("GET http://x/changed\n"); app.closeTab(1)
            assertEquals("prod/api.http", app.closePrompt!!.label)
        }
    }

    @Test fun switchingTabsKeepsAMessageAndOpeningAFileClearsTheNotice() {
        AppState().use { app ->
            app.open(file("a.http")); app.open(file("b.http"))
            app.resetChain()
            assertEquals(CHAIN_RESET_NOTICE, app.notice)
            app.closeTab(5)
            app.switchTo(0)
            assertEquals(CHAIN_RESET_NOTICE, app.notice)    // a switch does not wipe what was never read
            app.open(file("b.http"))                        // an open file: shown again, notice cleared
            assertNull(app.notice)
            app.resetChain(); app.open(file("c.http"))
            assertNull(app.notice)
        }
    }

    @Test fun theTabOnScreenIsTheFileRememberedForTheNextStart() {
        val cfg = dir.resolve("cfg")
        val p = SettingsPersistence(com.dan323.httpclient.adapters.JsonSettingsStore(cfg), debounceMillis = 20)
        val a = file("a.http"); val b = file("b.http"); val c = file("c.http")
        AppState(settings = p).use { app ->
            app.open(a); app.open(b); app.open(c)
            app.switchTo(0)
            assertEquals(canonical(a), p.current.lastFile)
            app.open(b)                                     // already open: still the one on screen
            assertEquals(canonical(b), p.current.lastFile)
            app.closeTab(1)                                 // b closes, c takes over
            assertEquals(canonical(c), p.current.lastFile)
        }
    }
}
