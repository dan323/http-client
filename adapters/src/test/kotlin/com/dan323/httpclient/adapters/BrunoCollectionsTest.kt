package com.dan323.httpclient.adapters

import com.dan323.httpclient.core.application.HttpFileParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrunoCollectionsTest {
    private val root = Files.createTempDirectory("httpc-bruno")
    private val source = Files.createDirectory(root.resolve("src"))
    private val target = root.resolve("out")

    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    private fun file(relative: String, text: String): Path {
        val f = source.resolve(relative)
        Files.createDirectories(f.parent)
        return Files.writeString(f, text.trimIndent() + "\n")
    }

    private fun request(name: String, seq: Int, url: String) = """
        meta {
          name: $name
          type: http
          seq: $seq
        }

        get {
          url: $url
          body: none
        }
    """.trimIndent() + "\n"

    @Test fun convertsFoldersEnvironmentsAndOrder() {
        file("bruno.json", """{ "version": "1", "name": "demo", "type": "collection" }""")
        file("second.bru", request("Second", 2, "{{host}}/b"))
        file("first.bru", request("First", 1, "{{host}}/a"))
        file("auth/login.bru", request("Login", 1, "{{host}}/login"))
        file("environments/dev.bru", """
            vars {
              host: http://localhost
            }
            vars:secret [
              token
            ]
        """)
        file("node_modules/skip.bru", request("Skip", 1, "http://skip"))

        val result = BrunoCollections.convert(source, target)

        assertEquals(listOf("auth/auth.http", "requests.http", "http-client.env.json").sorted(), result.files.sorted())
        val root = HttpFileParser().parse(Files.readString(target.resolve("requests.http")))
        assertEquals(listOf("First", "Second"), root.requests.map { it.name })
        assertEquals("{{host}}/login", HttpFileParser().parse(Files.readString(target.resolve("auth/auth.http"))).requests.single().url)
        assertTrue("\"host\": \"http://localhost\"" in Files.readString(target.resolve("http-client.env.json")))
        assertTrue(result.warnings.any { "token" in it && "http-client.private.env.json" in it })
        assertFalse(result.files.any { "skip" in it.lowercase() })
    }

    @Test fun duplicateNamesGetASuffixAndUnsupportedPartsAreReportedWithTheirFile() {
        file("bruno.json", "{}")
        file("a.bru", request("Same", 1, "http://h/a") + "\nscript:pre-request {\n  x();\n}\n")
        file("b.bru", request("Same", 2, "http://h/b"))
        file("collection.bru", "script:pre-request {\n  x();\n}\n")

        val result = BrunoCollections.convert(source, target)

        val names = HttpFileParser().parse(Files.readString(target.resolve("requests.http"))).requests.map { it.name }
        assertEquals(listOf("Same", "Same-2"), names)
        assertTrue(result.warnings.any { it.startsWith("a.bru:") && "script:pre-request" in it })
        assertTrue(result.warnings.any { it.startsWith("collection.bru:") })
    }

    @Test fun notACollectionIsRejected() {
        file("a.bru", request("A", 1, "http://h"))
        assertTrue(assertFailsWith<IOException> { BrunoCollections.read(source) }.message!!.contains("bruno.json"))
    }

    @Test fun neverWritesIntoANonEmptyTarget() {
        file("bruno.json", "{}")
        file("a.bru", request("A", 1, "http://h"))
        Files.createDirectories(target)
        Files.writeString(target.resolve("keep.txt"), "x")
        assertFailsWith<IOException> { BrunoCollections.convert(source, target) }
        assertEquals("x", Files.readString(target.resolve("keep.txt")))
    }

    @Test fun emptyCollectionWarns() {
        file("bruno.json", "{}")
        assertTrue(BrunoCollections.convert(source, target).warnings.any { "no requests" in it })
    }

    @Test fun symbolicLinksAreNotFollowed() {
        file("bruno.json", "{}")
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.writeString(outside.resolve("x.bru"), request("X", 1, "http://x").trimIndent())
        try { Files.createSymbolicLink(source.resolve("link"), outside) } catch (_: Exception) { assumeTrue(false) }
        val result = BrunoCollections.convert(source, target)
        assertTrue(result.files.isEmpty())
        assertTrue(result.warnings.any { "symbolic link" in it })
    }

    @Test fun unreadableOversizedAndSkippedFilesAreWarningsNotFailures() {
        file("bruno.json", "{}")
        file("ok.bru", request("Ok", 1, "http://h"))
        Files.write(source.resolve("binary.bru"), byteArrayOf(0x67, 0x65, 0x74, 0x20, 0xC3.toByte(), 0x28))
        Files.write(source.resolve("huge.bru"), ByteArray(1_000_001) { 'a'.code.toByte() })
        file("environments/big.bru", "vars {\n  a: b\n}\n" + "#".repeat(1_000_001))
        file(".hidden/h.bru", request("H", 1, "http://h"))
        file("node_modules/n.bru", request("N", 1, "http://n"))
        file("sub/environments/e.bru", request("E", 1, "http://e"))

        val result = BrunoCollections.convert(source, target)

        val w = result.warnings.joinToString("\n")
        for (expected in listOf("binary.bru: not readable", "huge.bru: larger", "environments/big.bru: larger", ".hidden: folder skipped", "node_modules: folder skipped")) {
            assertTrue(expected in w, "missing '$expected' in:\n$w")
        }
        assertTrue(result.files.containsAll(listOf("requests.http", "sub/environments/environments.http")), result.files.toString())
        assertFalse("http-client.env.json" in result.files)
    }

    @Test fun nestedRequestsPointBackToTheCollectionRoot() {
        file("bruno.json", "{}")
        file("a/b/up.bru", "post {\n  url: http://h\n  body: multipartForm\n}\n\nbody:multipart-form {\n  f: @file(data.json)\n}\n")
        BrunoCollections.convert(source, target)
        assertTrue("< ../../data.json" in Files.readString(target.resolve("a/b/b.http")))
    }

    @Test fun requestsInheritCollectionAndFolderSettings() {
        file("bruno.json", "{}")
        file("collection.bru", "headers {\n  X-All: 1\n}\n\nvars:pre-request {\n  region: eu\n}\n")
        file("api/folder.bru", "headers {\n  X-Api: 2\n}\n\nauth {\n  mode: bearer\n}\n\nauth:bearer {\n  token: {{t}}\n}\n")
        file("api/a.bru", "get {\n  url: http://h/a\n  auth: inherit\n}\n")
        file("api/deep/b.bru", "get {\n  url: http://h/b\n  auth: inherit\n}\n")
        file("root.bru", "get {\n  url: http://h/r\n}\n")

        val result = BrunoCollections.convert(source, target)

        val api = Files.readString(target.resolve("api/api.http"))
        assertTrue("X-All: 1" in api && "X-Api: 2" in api && "Authorization: Bearer {{t}}" in api && "@region = eu" in api, api)
        assertTrue("Authorization: Bearer {{t}}" in Files.readString(target.resolve("api/deep/deep.http")))
        val root = Files.readString(target.resolve("requests.http"))
        assertTrue("X-All: 1" in root && "X-Api" !in root && "Authorization" !in root, root)
        assertTrue(result.warnings.isEmpty(), result.warnings.toString())
    }

    @Test fun misplacedSettingsFilesAreReported() {
        file("bruno.json", "{}")
        file("folder.bru", "headers {\n  A: 1\n}\n")
        file("sub/collection.bru", "headers {\n  A: 1\n}\n")
        file("sub/r.bru", request("R", 1, "http://h"))
        val w = BrunoCollections.convert(source, target).warnings.joinToString("\n")
        assertTrue("folder.bru: ignored here" in w && "sub/collection.bru: ignored here" in w, w)
    }

    @Test fun collectionVariablesRankBelowTheEnvironment() {
        file("bruno.json", "{}")
        file("collection.bru", "vars:pre-request {\n  host: http://default\n  only: c\n}\n")
        file("environments/dev.bru", "vars {\n  host: http://dev\n}\n")
        file("a.bru", request("A", 1, "{{host}}/a"))
        file("api/folder.bru", "vars:pre-request {\n  host: http://folder\n}\n")
        file("api/b.bru", request("B", 1, "{{host}}/b"))

        val result = BrunoCollections.convert(source, target)

        val env = Files.readString(target.resolve("http-client.env.json"))
        assertTrue("\"host\": \"http://dev\"" in env && "\"only\": \"c\"" in env && "http://default" !in env, env)
        assertFalse("@host" in Files.readString(target.resolve("requests.http")))
        assertTrue("@host = http://folder" in Files.readString(target.resolve("api/api.http")))
        assertTrue(result.warnings.any { "copied into each environment" in it && "only" in it })
    }

    @Test fun collectionVariablesThatReferToOthersStayExpandable() {
        file("bruno.json", "{}")
        file("collection.bru", "vars:pre-request {\n  base: {{host}}/api\n  clash: {{host}}/c\n  token: {{fallback}}\n  plain: p\n}\n")
        file("environments/dev.bru", "vars {\n  host: http://dev\n  clash: own\n}\n\nvars:secret [\n  token\n]\n")
        file("a.bru", request("A", 1, "{{base}}/a"))

        val result = BrunoCollections.convert(source, target)

        val requests = Files.readString(target.resolve("requests.http"))
        assertTrue("@base = {{host}}/api" in requests, requests)
        assertFalse("@clash" in requests || "@plain" in requests || "@token" in requests, requests)
        val env = Files.readString(target.resolve("http-client.env.json"))
        assertTrue("\"plain\": \"p\"" in env && "\"clash\": \"own\"" in env && "{{host}}/api" !in env, env)
        assertTrue(result.warnings.any { "clash" in it && "token" in it && "not converted" in it }, result.warnings.toString())
    }
}
