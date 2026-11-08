package com.dan323.httpclient.viewmodel

import com.dan323.httpclient.core.application.EnvironmentChoice
import com.dan323.httpclient.core.port.out.EnvironmentFileException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentPickerModelTest {
    private class Fixture(var names: () -> List<String> = { listOf("dev", "prod") }) {
        var choice: EnvironmentChoice = EnvironmentChoice.None
        val listeners = mutableListOf<() -> Unit>()
        val backgroundJobs = ArrayDeque<() -> Unit>()
        val uiJobs = ArrayDeque<() -> Unit>()
        val rendered = mutableListOf<EnvironmentPickerState>()
        var onRender: (EnvironmentPickerState) -> Unit = {}
        val model = EnvironmentPickerModel(
            loadNames = { names() },
            getChoice = { choice },
            setChoice = { choice = it; listeners.toList().forEach { l -> l() } },
            subscribe = { l -> listeners += l; { listeners -= l } },
            background = { backgroundJobs += it },
            ui = { uiJobs += it },
            render = { rendered += it; onRender(it) },
        )
        fun pump() {
            while (backgroundJobs.isNotEmpty() || uiJobs.isNotEmpty()) {
                backgroundJobs.removeFirstOrNull()?.invoke()
                uiJobs.removeFirstOrNull()?.invoke()
            }
        }
    }

    @Test fun initialStateIsNoneOnly() {
        val f = Fixture()
        assertEquals(listOf(NONE_LABEL), f.model.state.items.map { it.label })
        assertEquals(0, f.model.state.selected)
        assertNull(f.model.state.hint)
    }

    @Test fun startLoadsOffThreadAndRendersOnUi() {
        val f = Fixture()
        f.model.start()
        assertTrue(f.rendered.isEmpty())
        assertEquals(1, f.backgroundJobs.size)
        f.pump()
        assertEquals(listOf(NONE_LABEL, "dev", "prod"), f.rendered.single().items.map { it.label })
        assertEquals(1, f.listeners.size)
    }

    @Test fun existingChoiceIsSelectedWithoutHint() {
        val f = Fixture().apply { choice = EnvironmentChoice.Named("prod") }
        f.model.start(); f.pump()
        assertEquals(2, f.model.state.selected)
        assertNull(f.model.state.hint)
    }

    @Test fun missingChoiceIsShownWithHint() {
        val f = Fixture().apply { choice = EnvironmentChoice.Named("old") }
        f.model.start(); f.pump()
        val s = f.model.state
        assertEquals("old (missing)", s.items[s.selected].label)
        assertTrue(assertNotNull(s.hint).contains("'old' is not defined"))
    }

    @Test fun envFileErrorBecomesHintAndKeepsChoiceLabel() {
        val f = Fixture({ throw EnvironmentFileException(java.nio.file.Path.of("e.json"), "bad json") }).apply { choice = EnvironmentChoice.Named("dev") }
        f.model.start(); f.pump()
        val s = f.model.state
        assertTrue(assertNotNull(s.hint).endsWith("bad json"))
        assertEquals("dev", s.items[s.selected].label)
    }

    @Test fun anyOtherLoaderFailureIsAlsoAHint() {
        val f = Fixture({ throw IllegalStateException() })
        f.model.start(); f.pump()
        assertEquals("Cannot read environments: IllegalStateException", f.model.state.hint)
        assertEquals(listOf(NONE_LABEL), f.model.state.items.map { it.label })
        val g = Fixture({ throw IllegalArgumentException("not a local path") })
        g.model.start(); g.pump()
        assertTrue(assertNotNull(g.model.state.hint).contains("not a local path"))
    }

    @Test fun selectAppliesTheChoice() {
        val f = Fixture()
        f.model.start(); f.pump()
        f.model.select(1)
        assertEquals(EnvironmentChoice.Named("dev"), f.choice)
        f.model.select(0)
        assertEquals(EnvironmentChoice.None, f.choice)
        f.model.select(99)
        f.model.select(-1)
        assertEquals(EnvironmentChoice.None, f.choice)
    }

    @Test fun selectionFiredByRenderingIsIgnored() {
        val f = Fixture()
        f.onRender = { f.model.select(2) }
        f.model.start(); f.pump()
        assertEquals(EnvironmentChoice.None, f.choice)
        f.onRender = {}
        f.model.select(2)
        assertEquals(EnvironmentChoice.Named("prod"), f.choice)
    }

    @Test fun choiceChangedElsewhereReloads() {
        val f = Fixture()
        f.model.start(); f.pump()
        f.rendered.clear()
        f.choice = EnvironmentChoice.Named("prod")
        f.listeners.toList().forEach { it() }
        f.pump()
        assertEquals(2, f.rendered.single().selected)
    }

    @Test fun choiceNotationMatchingTheShownEntryDoesNotReload() {
        val f = Fixture()
        f.model.start(); f.pump()
        f.rendered.clear()
        f.listeners.toList().forEach { it() }
        f.pump()
        assertTrue(f.rendered.isEmpty())
    }

    @Test fun reloadPicksUpEditedEnvFile() {
        val f = Fixture()
        f.model.start(); f.pump()
        f.names = { listOf("staging") }
        f.model.reload(); f.pump()
        assertEquals(listOf(NONE_LABEL, "staging"), f.model.state.items.map { it.label })
    }

    @Test fun overtakenReloadIsDropped() {
        val f = Fixture()
        f.model.reload()
        f.names = { listOf("new") }
        f.model.reload()
        val bg = f.backgroundJobs.toList(); f.backgroundJobs.clear(); bg.forEach { it() }
        f.pump()
        assertEquals(1, f.rendered.size)
        assertEquals(listOf(NONE_LABEL, "new"), f.rendered.single().items.map { it.label })
    }

    @Test fun disposeUnsubscribesAndDropsPendingAndLaterWork() {
        val f = Fixture()
        f.model.start()
        f.model.dispose()
        assertTrue(f.listeners.isEmpty())
        f.pump()
        assertTrue(f.rendered.isEmpty())
        f.model.reload()
        assertTrue(f.backgroundJobs.isEmpty())
        f.model.select(1)
        assertEquals(EnvironmentChoice.None, f.choice)
        f.model.dispose()
    }

    @Test fun listenerFiringAfterDisposeDoesNothing() {
        val f = Fixture()
        f.model.start(); f.pump()
        val l = f.listeners.single()
        f.model.dispose()
        assertTrue(f.listeners.isEmpty())
        f.choice = EnvironmentChoice.Named("dev")
        l()
        f.pump()
        assertEquals(1, f.rendered.size)
    }

    @Test fun listenerJobQueuedBeforeDisposeIsDropped() {
        val f = Fixture()
        f.model.start(); f.pump()
        f.choice = EnvironmentChoice.Named("dev")
        f.listeners.toList().forEach { it() }
        f.model.dispose()
        f.pump()
        assertEquals(1, f.rendered.size)
    }
}
