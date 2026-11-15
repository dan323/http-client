package com.dan323.httpclient.desktop

import com.dan323.httpclient.adapters.JsonEnvironmentFileStore
import com.dan323.httpclient.core.port.out.EnvironmentFileContent
import com.dan323.httpclient.core.port.out.EnvironmentFileException
import com.dan323.httpclient.core.port.out.EnvironmentFileStore
import com.dan323.httpclient.core.port.out.EnvironmentFileStoreLocator
import com.dan323.httpclient.core.port.out.EnvironmentScope
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How [AppState] opens, keeps and closes the environment editor when files switch or the folder cannot be read. */
class AppStateEnvironmentEditorTest {
    /** A store whose reads wait for [gate], so a test can switch files while the editor is loading. */
    private class SlowReadStore(private val real: EnvironmentFileStore, val entered: CountDownLatch, val gate: CountDownLatch) : EnvironmentFileStore by real {
        override fun read(scope: EnvironmentScope): EnvironmentFileContent {
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
            return real.read(scope)
        }
    }

    @Test fun `an editor whose load finishes after another file came on screen is not shown`() {
        val root = Files.createTempDirectory("httpc-appenv")
        try {
            Files.writeString(root.resolve("http-client.env.json"), """{"dev":{"base":"http://x"}}""")
            val a = root.resolve("a.http").also { Files.writeString(it, "GET http://a\n") }
            val b = root.resolve("b.http").also { Files.writeString(it, "GET http://b\n") }
            val entered = CountDownLatch(1)
            val gate = CountDownLatch(1)
            val state = AppState(environmentStores = EnvironmentFileStoreLocator { f -> SlowReadStore(JsonEnvironmentFileStore(f.parent), entered, gate) })
            state.open(a)
            val loading = thread { state.showEnvironmentEditor(true) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            state.open(b)                      // the user switches files while the files are read
            gate.countDown()
            loading.join(10_000)
            assertNull(state.envEditor, "the editor of the first file must not appear on the second")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `a close while the files are read also cancels the editor`() {
        val root = Files.createTempDirectory("httpc-appenv")
        try {
            Files.writeString(root.resolve("http-client.env.json"), """{"dev":{"base":"http://x"}}""")
            val a = root.resolve("a.http").also { Files.writeString(it, "GET http://a\n") }
            val entered = CountDownLatch(1)
            val gate = CountDownLatch(1)
            val state = AppState(environmentStores = EnvironmentFileStoreLocator { f -> SlowReadStore(JsonEnvironmentFileStore(f.parent), entered, gate) })
            state.open(a)
            val loading = thread { state.showEnvironmentEditor(true) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            state.showEnvironmentEditor(false)
            gate.countDown()
            loading.join(10_000)
            assertNull(state.envEditor)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `a folder that cannot be opened is reported and no editor appears`() {
        val root = Files.createTempDirectory("httpc-appenv")
        try {
            val a = root.resolve("a.http").also { Files.writeString(it, "GET http://a\n") }
            val state = AppState(environmentStores = EnvironmentFileStoreLocator { f -> throw EnvironmentFileException(f.parent.resolve("http-client.env.json"), "the link cannot be followed") })
            state.open(a)
            state.showEnvironmentEditor(true)
            assertNull(state.envEditor)
            assertTrue("the link cannot be followed" in state.message.orEmpty(), state.message)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `opening the editor closes History and a normal open shows it`() {
        val root = Files.createTempDirectory("httpc-appenv")
        try {
            Files.writeString(root.resolve("http-client.env.json"), """{"dev":{"base":"http://x"}}""")
            val a = root.resolve("a.http").also { Files.writeString(it, "GET http://a\n") }
            val state = AppState()
            state.open(a)
            state.showEnvironmentEditor(true)
            assertNotNull(state.envEditor)
            assertEquals(listOf("dev"), state.envEditor?.draft?.environments?.map { it.name })
        } finally { root.toFile().deleteRecursively() }
    }
}
