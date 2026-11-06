package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.HttpFileParser
import com.dan323.httpclient.core.application.RequestRunner
import com.dan323.httpclient.core.application.RequestSession
import com.dan323.httpclient.core.application.RunOutcome
import com.dan323.httpclient.core.domain.HttpResponse
import com.dan323.httpclient.core.port.out.HttpExecutor
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalFileReaderTest {
    private val tmp: Path = Files.createTempDirectory("reader").toRealPath()
    private val dir: Path = Files.createDirectory(tmp.resolve("proj"))
    private val outside: Path = Files.createDirectory(tmp.resolve("other"))

    init {
        Files.writeString(dir.resolve("ok.txt"), "ok")
        Files.createDirectory(dir.resolve("sub"))
        Files.writeString(dir.resolve("sub/in.txt"), "in")
        Files.writeString(outside.resolve("secret.txt"), "TOPSECRET")
    }

    private fun reader(vararg roots: Path) = LocalFileReader(dir, FileAccessPolicy(roots.toList()))

    /** Creates a symlink; on non-Windows failing to do so fails the test, on Windows (needs privilege) it is reported as skipped. */
    private fun symlink(link: Path, target: Path) {
        try { Files.createSymbolicLink(link, target) } catch (e: Exception) {
            org.junit.jupiter.api.Assumptions.assumeFalse(File.separatorChar == '\\', "symlinks unavailable: $e")
            throw AssertionError("could not create symlink on a platform that must support them", e)
        }
    }

    @Test fun readsInsideAndNormalisedInside() {
        assertContentEquals("ok".toByteArray(), reader().read("./ok.txt"))
        assertContentEquals("in".toByteArray(), reader().read("sub/../sub/in.txt"))
    }

    @Test fun rejectsTraversalNamingPathAndHint() {
        val e = assertFailsWith<FileAccessDeniedException> { reader().read("../other/secret.txt") }
        assertTrue("../other/secret.txt" in e.message!!)
        assertTrue(FileAccessPolicy.ALLOW_PROPERTY in e.message!!)
        assertFalse("TOPSECRET" in e.message!!)
        val cli = LocalFileReader(dir, FileAccessPolicy(emptyList(), FileAccessPolicy.CLI_HINT))
        assertTrue("--allow-file-root" in assertFailsWith<FileAccessDeniedException> { cli.read("../other/secret.txt") }.message!!)
    }

    @Test fun rejectsAbsolutePath() {
        assertFailsWith<FileAccessDeniedException> { reader().read(outside.resolve("secret.txt").toString()) }
    }

    @Test fun rejectsSiblingWithSharedPrefix() {
        val sibling = Files.createDirectory(tmp.resolve("proj-evil"))
        Files.writeString(sibling.resolve("x.txt"), "x")
        assertFailsWith<FileAccessDeniedException> { reader().read("../proj-evil/x.txt") }
    }

    @Test fun rejectsMissingFileOutside() {
        assertFailsWith<FileAccessDeniedException> { reader().read("../nope/missing.txt") }
    }

    @Test fun missingFileInsideIsPlainNotFound() {
        assertFailsWith<NoSuchFileException> { reader().read("nope/missing.txt") }
    }

    @Test fun rejectsFileSymlinkEscape() {
        symlink(dir.resolve("link.txt"), outside.resolve("secret.txt"))
        assertFailsWith<FileAccessDeniedException> { reader().read("link.txt") }
    }

    @Test fun rejectsDirectorySymlinkEscape() {
        symlink(dir.resolve("linkdir"), outside)
        assertFailsWith<FileAccessDeniedException> { reader().read("linkdir/secret.txt") }
        assertFailsWith<FileAccessDeniedException> { reader().read("linkdir/new.txt") }
    }

    @Test fun symlinkInsideIsFine() {
        symlink(dir.resolve("alias.txt"), dir.resolve("ok.txt"))
        assertContentEquals("ok".toByteArray(), reader().read("alias.txt"))
    }

    @Test fun allowedRootPermitsOutsideRelativeAndAbsolute() {
        val r = reader(outside)
        assertContentEquals("TOPSECRET".toByteArray(), r.read("../other/secret.txt"))
        assertContentEquals("TOPSECRET".toByteArray(), r.read(outside.resolve("secret.txt").toString()))
        assertFailsWith<FileAccessDeniedException> { r.read("../../x") }
    }

    @Test fun symlinkedAllowedRootStillWorks() {
        val aliasRoot = tmp.resolve("rootlink")
        symlink(aliasRoot, outside)
        assertContentEquals("TOPSECRET".toByteArray(), reader(aliasRoot).read("../other/secret.txt"))
    }

    @Test fun windowsStyleSeparators() {
        if (File.separatorChar != '\\') return
        assertContentEquals("in".toByteArray(), reader().read("sub\\in.txt"))
        assertFailsWith<FileAccessDeniedException> { reader().read("..\\other\\secret.txt") }
    }

    /** A path that is absolute on this platform ("/a" has no drive letter on Windows, so it is relative there). */
    private fun absRoot(name: String): String = tmp.toAbsolutePath().root.resolve(name).toString()

    @Test fun parsesRootLists() {
        val a = absRoot("a"); val b = absRoot("b")
        assertEquals(listOf(Path.of(a), Path.of(b)), FileAccessPolicy.parseRoots(" $a ,, $b", ","))
        if (File.separatorChar == '\\') {
            assertEquals(listOf("C:\\a", "D:\\b"), FileAccessPolicy.parseRoots("C:\\a;D:\\b", ";").map { it.toString() })
        }
        assertTrue(FileAccessPolicy.parseRoots(null).isEmpty())
        val p = FileAccessPolicy.fromConfiguration({ "${absRoot("x")}${File.pathSeparator}${absRoot("y")}" }, { null })
        assertEquals(2, p.extraRoots.size)
        val z = absRoot("z")
        val fromEnv = FileAccessPolicy.fromConfiguration({ null }, { if (it == FileAccessPolicy.ALLOW_ENV_VAR) z else null })
        assertEquals(listOf(Path.of(z)), fromEnv.extraRoots)
    }

    @Test fun endToEndRejectedReadIsAFailedRequestWithoutLeaking() {
        var sent = false
        val exec = HttpExecutor { sent = true; HttpResponse(200, emptyList(), ByteArray(0), 0) }
        val session = RequestSession(RequestRunner(exec, LocalFileReader(dir)), exec, KotlinxJsonExtractor())
        val file = HttpFileParser().parse("POST http://localhost/x\n\n< ../other/secret.txt\n")
        val out = session.runAll(file.requests, emptyMap()).single()
        val failure = assertIs<RunOutcome.Failure>(out)
        assertTrue("../other/secret.txt" in failure.message)
        assertFalse("TOPSECRET" in failure.message)
        assertFalse(sent)
    }

    @Test fun rejectsBlankRelativeAndFilesystemRoots() {
        assertFailsWith<FileAccessConfigException> { FileAccessPolicy.validateRoot("  ", requireAbsolute = false) }
        assertFailsWith<FileAccessConfigException> { FileAccessPolicy.parseRoots("relative/dir") }
        val e = assertFailsWith<FileAccessConfigException> { FileAccessPolicy.parseRoots("/") }
        assertTrue("'/'" in e.message!!)
        assertFailsWith<FileAccessConfigException> { FileAccessPolicy.validateRoot("/", requireAbsolute = false) }
        // relative roots are allowed where the shell's working directory is meaningful (CLI)
        FileAccessPolicy.validateRoot("rel", requireAbsolute = false)
    }

    @Test fun expandsTildeToHome() {
        val home = absRoot("home${File.separator}u")
        assertEquals(listOf(Path.of(home, "shared")), FileAccessPolicy.parseRoots("~/shared", ":", home))
        assertFailsWith<FileAccessConfigException> { FileAccessPolicy.parseRoots("~user/x", ":", home) }
    }

    @Test fun malformedEntryIsAConfigErrorNotAnException() {
        // NUL is an invalid path character on every platform
        val e = assertFailsWith<FileAccessConfigException> { FileAccessPolicy.parseRoots("/ok:/bad\u0000x") }
        assertTrue("/bad" in e.message!!)
        val r = FileAccessPolicy.load({ "/bad\u0000x" }, { null })
        assertTrue(r.isFailure)
        assertTrue(FileAccessPolicy.load({ null }, { null }).isSuccess)
    }

    @Test fun symlinkedBaseFolderWorks() {
        val link = tmp.resolve("projlink")
        symlink(link, dir)
        val r = LocalFileReader(link)
        assertContentEquals("ok".toByteArray(), r.read("ok.txt"))
        assertFailsWith<FileAccessDeniedException> { r.read("../other/secret.txt") }
    }

    @Test fun emptyAndDotPathsAreNotContentReads() {
        for (p in listOf("", ".")) {
            assertFailsWith<java.io.IOException> { reader().read(p) }
        }
    }

    @Test fun danglingSymlinkToOutsideIsDenied() {
        symlink(dir.resolve("dangling"), outside.resolve("not-yet.txt"))
        assertFailsWith<FileAccessDeniedException> { reader().read("dangling") }
    }

    @Test fun danglingSymlinkInsideIsNotFound() {
        symlink(dir.resolve("dangling2"), dir.resolve("later.txt"))
        assertFailsWith<NoSuchFileException> { reader().read("dangling2") }
    }
}
