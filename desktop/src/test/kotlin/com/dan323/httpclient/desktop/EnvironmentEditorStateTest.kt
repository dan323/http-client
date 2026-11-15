package com.dan323.httpclient.desktop

import com.dan323.httpclient.adapters.JsonEnvironmentFileStore
import com.dan323.httpclient.core.domain.EnvironmentEdit
import com.dan323.httpclient.core.port.out.EnvironmentFileStore
import com.dan323.httpclient.core.port.out.EnvironmentScope
import com.dan323.httpclient.viewmodel.EnvironmentEditor
import com.dan323.httpclient.viewmodel.SaveOutcome
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentEditorStateTest {
    /** A store whose first write waits for [gate], so a test can act while a save is running. */
    private class SlowStore(private val real: EnvironmentFileStore, val entered: CountDownLatch, val gate: CountDownLatch) : EnvironmentFileStore by real {
        override fun write(scope: EnvironmentScope, edits: List<EnvironmentEdit>, expectedVersion: String?): String? {
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
            return real.write(scope, edits, expectedVersion)
        }
    }

    @Test fun `edits are refused while a save runs and work again after it`() {
        val root = Files.createTempDirectory("httpc-envstate")
        try {
            Files.writeString(root.resolve("http-client.env.json"), """{"dev":{"base":"http://x"}}""")
            val entered = CountDownLatch(1)
            val gate = CountDownLatch(1)
            val state = EnvironmentEditorState(EnvironmentEditor(SlowStore(JsonEnvironmentFileStore(root), entered, gate)))
            state.load()
            state.change { it.addEnvironment("prod") }
            var outcome: SaveOutcome? = null
            val saving = thread { outcome = state.save() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(state.busy)
            state.change { it.addEnvironment("late") }          // refused: the save is writing
            assertNull(state.save(), "a second save does nothing")
            gate.countDown()
            saving.join(10_000)
            assertIs<SaveOutcome.Saved>(outcome)
            assertEquals(listOf("dev", "prod"), state.draft.environments.map { it.name }, "the edit made during the save was not applied")
            state.change { it.addEnvironment("later") }
            assertEquals(listOf("dev", "prod", "later"), state.draft.environments.map { it.name })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `loading again drops the draft and the status of the last save`() {
        val root = Files.createTempDirectory("httpc-envstate")
        try {
            Files.writeString(root.resolve("http-client.env.json"), """{"dev":{"base":"http://x"}}""")
            val state = EnvironmentEditorState(EnvironmentEditor(JsonEnvironmentFileStore(root)))
            state.load()
            state.change { it.addEnvironment("prod") }
            state.save()
            assertEquals("Saved the shared file.", state.status)
            state.change { it.addEnvironment("draft") }
            state.load()
            assertNull(state.status)
            assertEquals(emptyList(), state.problems)
            assertEquals(listOf("dev", "prod"), state.draft.environments.map { it.name })
        } finally { root.toFile().deleteRecursively() }
    }
}
