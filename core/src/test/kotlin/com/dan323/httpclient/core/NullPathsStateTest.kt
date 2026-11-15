package com.dan323.httpclient.core

import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.application.environments
import com.dan323.httpclient.core.application.withDependencies
import com.dan323.httpclient.core.domain.EditableEnvironments
import com.dan323.httpclient.core.domain.Environment
import com.dan323.httpclient.core.domain.EnvironmentEdit
import com.dan323.httpclient.core.domain.HiddenContent
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.domain.RequestTemplate
import com.dan323.httpclient.core.domain.applying
import com.dan323.httpclient.core.domain.problem
import com.dan323.httpclient.core.port.out.DesktopSettings
import com.dan323.httpclient.core.port.out.EnvironmentLocator
import com.dan323.httpclient.core.port.out.EnvironmentRepository
import com.dan323.httpclient.core.port.out.HttpExecutor
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Absent values in the application state: no environment file, no stored choice, no link store, a reference that is no file variable. */
class NullPathsStateTest {
    // Dependencies
    @Test fun `a reference that is neither a response nor a file variable adds nothing`() {
        val login = RequestTemplate("login", "GET", "http://x/{{unknown}}")
        val other = RequestTemplate("other", "GET", "http://x/o")
        assertEquals(listOf(login), withDependencies(listOf(other, login), listOf(login)))
    }

    // EnvironmentChoice
    @Test fun `environments are empty when the locator finds no file and listed when it does`() {
        val file = Path.of("a.http")
        assertEquals(emptyList(), EnvironmentLocator { null }.environments(file))
        val dev = Environment("dev", mapOf("a" to "1"))
        assertEquals(listOf(dev), EnvironmentLocator { EnvironmentRepository { listOf(dev) } }.environments(file))
    }

    // EnvironmentEdit
    @Test fun `a variable edit with a bad environment name is refused for the environment`() {
        assertEquals("An environment name cannot be empty.", EnvironmentEdit.SetVariable("", "ok", "1").problem())
        assertEquals("A variable name cannot be empty.", EnvironmentEdit.SetVariable("dev", "", "1").problem())
        assertNull(EnvironmentEdit.SetVariable("dev", "ok", "1").problem())
    }

    @Test fun `renaming an environment keeps what is hidden under its new name only when something was hidden`() {
        val base: EditableEnvironments = linkedMapOf("a" to linkedMapOf("x" to "1"), "b" to linkedMapOf("y" to "2"))
        val renamed = base.applying(listOf(EnvironmentEdit.RenameEnvironment("a", "c")), HiddenContent.NONE)
        assertEquals(listOf("c", "b"), renamed.keys.toList())
        assertEquals("1", renamed.getValue("c").getValue("x"))
    }

    // RequestSession
    @Test fun `a request with a response link and no link store reports it instead of failing`() {
        val executor = HttpExecutor { HttpResponse(200, emptyList(), ByteArray(0), 1) }
        val session = RequestSession(RequestRunner(executor) { error("no files") }, executor) { _, _ -> null }.withPersistence(null, null)
        val request = RequestTemplate("a", "GET", "http://h", responseLinks = listOf("out.json"), startLine = 4)
        val outcome = session.runAll(listOf(request), emptyMap()).single()
        assertIs<RunOutcome.Success>(outcome)
        assertEquals("Cannot keep the response link: keeping response links is not available here", outcome.linkError)
    }

    // DesktopSettings
    @Test fun `opening a file moves its remembered environment to the most recent end and leaves others alone`() {
        val s = DesktopSettings(environments = linkedMapOf("a" to "dev", "b" to "prod", "c" to "qa"))
        assertEquals(listOf("b", "c", "a"), s.opened("a").environments.keys.toList())
        assertEquals(listOf("a", "b", "c"), s.opened("z").environments.keys.toList())
        assertEquals(mapOf("a" to "dev", "b" to "prod", "c" to "qa"), s.opened("z").environments)
    }

    @Test fun `a remembered environment is replaced, forgotten and bounded to the most recent ones`() {
        val s = DesktopSettings().withEnvironment("a", "dev").withEnvironment("b", "prod").withEnvironment("a", "qa")
        assertEquals(listOf("b" to "prod", "a" to "qa"), s.environments.toList())
        assertEquals(mapOf("b" to "prod"), s.withEnvironment("a", null).environments)
        val many = (1..DesktopSettings.MAX_ENVIRONMENTS + 10).associate { "f$it" to "e" }
        val kept = DesktopSettings.boundedEnvironments(many)
        assertEquals(DesktopSettings.MAX_ENVIRONMENTS, kept.size)
        assertEquals("f11", kept.keys.first())
        val few = many.entries.take(3).associate { it.key to it.value }
        assertEquals(few, DesktopSettings.boundedEnvironments(few))
    }

    @Test fun `recent files are cut to the limit keeping the first ones`() {
        val paths = (1..15).map { "f$it" }
        assertEquals(paths.take(DesktopSettings.MAX_RECENT), DesktopSettings.bounded(paths))
        assertEquals(listOf("a", "b"), DesktopSettings.bounded(listOf("a", "b", "a")))
    }
}
