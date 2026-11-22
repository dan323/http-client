package com.dan323.httpclient.mcp

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Starts the server wiring against a local HTTP server and tries to break each rule of docs/mcp-safety-model.md. */
class McpServerTest {
    private val root = Files.createTempDirectory("mcp-root").toRealPath()
    private val outside = Files.createTempDirectory("mcp-outside").toRealPath()
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val base get() = "http://127.0.0.1:${http.address.port}"
    private val hits = java.util.concurrent.atomic.AtomicInteger()
    private val closers = mutableListOf<AutoCloseable>()
    private var id = 0

    init {
        http.createContext("/echo") { ex ->
            hits.incrementAndGet()
            val sent = ex.requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value.joinToString()}" } + "\n" + ex.requestURI
            ex.responseHeaders.add("X-Echo", ex.requestHeaders.getFirst("X-Secret") ?: "none")
            val b = sent.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        http.createContext("/big") { ex -> hits.incrementAndGet(); val b = "x".repeat(5000).toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        http.createContext("/redir") { ex -> ex.responseHeaders.add("Location", "http://example.invalid/steal"); ex.sendResponseHeaders(302, -1); ex.close() }
        http.createContext("/post") { ex -> hits.incrementAndGet(); ex.sendResponseHeaders(204, -1); ex.close() }
        http.start()
    }

    @AfterTest fun tearDown() {
        http.stop(0); closers.forEach { it.close() }
        root.toFile().deleteRecursively(); outside.toFile().deleteRecursively()
    }

    private fun server(vararg extra: String, configure: ServerOptions.() -> ServerOptions = { this }): McpServer {
        val options = ServerOptions.parse(listOf("--root", root.toString()) + extra).getOrThrow().configure()
        val (s, c) = buildServer(options); closers += c; return s
    }

    private fun write(name: String, text: String) = root.resolve(name).also { Files.writeString(it, text.replace("BASE", base)) }

    private fun call(s: McpServer, tool: String, vararg args: Pair<String, String>): Pair<String, Boolean> {
        val params = JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive(tool),
            "arguments" to JsonObject(args.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) })))
        val reply = Json.parseToJsonElement(s.handle(JsonObject(mapOf(
            "jsonrpc" to kotlinx.serialization.json.JsonPrimitive("2.0"), "id" to kotlinx.serialization.json.JsonPrimitive(++id),
            "method" to kotlinx.serialization.json.JsonPrimitive("tools/call"), "params" to params)).toString())!!).jsonObject
        val result = reply["result"]!!.jsonObject
        return result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content to (result["isError"]!!.jsonPrimitive.content == "true")
    }

    // ---- protocol

    @Test fun initializeListsToolsAndNotificationsGetNoReply() {
        val s = server()
        val init = Json.parseToJsonElement(s.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")!!).jsonObject
        assertEquals("2025-06-18", init["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        assertNull(s.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        val list = Json.parseToJsonElement(s.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")!!).jsonObject
        val names = list["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(setOf("list_requests", "list_environments", "analyze", "run", "run_all"), names.toSet())
    }

    @Test fun unknownVersionFallsBackAndBadMessagesGetErrors() {
        val s = server()
        assertTrue("2025-06-18" in s.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999"}}""")!!)
        assertTrue("-32700" in s.handle("{nope")!!)
        assertTrue("-32600" in s.handle("[]")!!)
        assertTrue("-32601" in s.handle("""{"jsonrpc":"2.0","id":3,"method":"resources/list"}""")!!)
        assertTrue("-32602" in s.handle("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"rm_rf"}}""")!!)
        assertEquals("""{"jsonrpc":"2.0","id":5,"result":{}}""", s.handle("""{"jsonrpc":"2.0","id":5,"method":"ping"}"""))
    }

    // ---- rule 1: roots

    @Test fun pathsOutsideTheRootsAreRefused() {
        val s = server()
        Files.writeString(outside.resolve("o.http"), "GET $base/echo\n")
        Files.createSymbolicLink(root.resolve("link.http"), outside.resolve("o.http"))
        write("notes.txt", "x")
        for (p in listOf("../${outside.fileName}/o.http", outside.resolve("o.http").toString(), "link.http", "notes.txt", "missing.http")) {
            val (text, error) = call(s, "list_requests", "file" to p)
            assertTrue(error, p); assertFalse("GET" in text, p)
        }
        assertEquals(0, hits.get())
    }

    @Test fun anAbsolutePathInsideARootWorks() {
        val f = write("a.http", "### one\nGET BASE/echo\n")
        val (text, error) = call(server(), "list_requests", "file" to f.toString())
        assertFalse(error); assertTrue("/echo" in text)
    }

    @Test fun listRequestsShowsTheRequestsAsWritten() {
        write("a.http", "@host = BASE\n### one\nGET {{host}}/echo?k={{secret}}\n")
        val (text, _) = call(server(), "list_requests", "file" to "a.http")
        assertTrue("{{host}}/echo?k={{secret}}" in text)
    }

    @Test fun anEnvironmentFileCannotBeEchoedAsABody() {
        write("http-client.private.env.json", """{"dev":{"k":"TOPSECRET"}}""")
        write("a.http", "POST BASE/echo\n\n< ./http-client.private.env.json\n")
        val (text, error) = call(server("--allow-write"), "run", "file" to "a.http", "request" to "1")
        assertFalse("TOPSECRET" in text)
        assertTrue("environment file" in text || error)
    }

    // ---- rule 2: hosts

    @Test fun aRedirectToAHostThatIsNotAllowedIsNotFollowed() {
        write("a.http", "GET BASE/redir\n")
        val (text, _) = call(server(), "run", "file" to "a.http", "request" to "1")
        assertTrue("example.invalid" in text && "not an allowed host" in text, text)
    }

    @Test fun aRequestToAHostThatIsNotAllowedSendsNothing() {
        write("a.http", "GET http://example.com/\n")
        val (text, error) = call(server(), "run", "file" to "a.http", "request" to "1")
        assertTrue(error); assertTrue("not an allowed host" in text)
    }

    @Test fun aProxyIsRefused() {
        write("a.http", "# @proxy http://127.0.0.1:9\nGET BASE/echo\n")
        val (text, error) = call(server(), "run", "file" to "a.http", "request" to "1")
        assertTrue(error); assertTrue("@proxy" in text); assertEquals(0, hits.get())
    }

    // ---- rule 3: read only

    @Test fun aPostWithoutAllowWriteIsRefusedBeforeAnythingIsSent() {
        write("a.http", "### first\nGET BASE/echo\n\n### second\nPOST BASE/post\n")
        val (text, error) = call(server(), "run_all", "file" to "a.http")
        assertTrue(error); assertTrue("--allow-write" in text)
        assertEquals(0, hits.get(), "the GET must not go out when a later request is refused")
    }

    @Test fun aPostRunsWithAllowWrite() {
        write("a.http", "POST BASE/post\n")
        val (text, error) = call(server("--allow-write"), "run", "file" to "a.http", "request" to "1")
        assertFalse(error); assertTrue("\"status\": 204" in text); assertEquals(1, hits.get())
    }

    @Test fun aChainedPostIsRefusedToo() {
        write("a.http", "### login\n# @name login\nPOST BASE/post\n\n### use\nGET BASE/echo?x={{login.response.status}}\n")
        val (_, error) = call(server(), "run", "file" to "a.http", "request" to "2")
        assertTrue(error); assertEquals(0, hits.get())
    }

    // ---- rule 4: private values

    @Test fun listEnvironmentsReturnsNamesOnly() {
        write("http-client.env.json", """{"dev":{"host":"h"}}""")
        write("http-client.private.env.json", """{"dev":{"key":"TOPSECRET"}}""")
        write("a.http", "GET BASE/echo\n")
        val (text, _) = call(server(), "list_environments", "file" to "a.http")
        assertTrue("dev" in text); assertFalse("TOPSECRET" in text)
    }

    @Test fun aPrivateValueEchoedBackByTheServerIsRedacted() {
        write("http-client.env.json", """{"dev":{"base":"$base"}}""")
        write("http-client.private.env.json", """{"dev":{"key":"TOPSECRET"}}""")
        write("a.http", "GET {{base}}/echo?k={{key}}\nX-Secret: {{key}}\n")
        val (text, error) = call(server(), "run", "file" to "a.http", "request" to "1", "environment" to "dev")
        assertFalse(error, text)
        assertFalse("TOPSECRET" in text, text)
        assertTrue("{{key}}" in text, text)
    }

    @Test fun anUnknownEnvironmentNamesTheAvailableOnes() {
        write("http-client.env.json", """{"dev":{"host":"h"}}""")
        write("a.http", "GET BASE/echo\n")
        val (text, error) = call(server(), "run", "file" to "a.http", "request" to "1", "environment" to "prod")
        assertTrue(error); assertTrue("dev" in text)
    }

    @Test fun analyzeSendsNothingAndReportsUndefinedVariables() {
        write("a.http", "GET BASE/echo?k={{nope}}\n")
        val (text, error) = call(server(), "analyze", "file" to "a.http")
        assertFalse(error); assertTrue("undefined-variable" in text); assertEquals(0, hits.get())
    }

    // ---- rule 5, 6: no writes, no ambient authority

    @Test fun saveDirectivesWriteNothing() {
        write("a.http", "GET BASE/echo\n>> out.txt\n")
        val (text, _) = call(server(), "run", "file" to "a.http", "request" to "1")
        assertTrue("saveError" in text); assertFalse(Files.exists(root.resolve("out.txt")))
    }

    @Test fun processEnvironmentIsNotReadableByDefault() {
        write("a.http", "GET BASE/echo?h={{\$env.HOME}}\n")
        val (text, _) = call(server(), "run", "file" to "a.http", "request" to "1")
        assertFalse(System.getenv("HOME") in text)
    }

    // ---- rule 7: bounds

    @Test fun aBodyIsCutAtTheLimit() {
        write("a.http", "GET BASE/big\n")
        val (text, _) = call(server("--max-body-bytes", "100"), "run", "file" to "a.http", "request" to "1")
        assertTrue("bodyTruncated" in text); assertFalse("x".repeat(101) in text)
    }

    @Test fun aFileLargerThanTheRequestCapIsRefusedBeforeSending() {
        write("a.http", (1..3).joinToString("\n") { "### r$it\nGET BASE/echo\n" })
        val (text, error) = call(server("--max-requests", "2"), "run_all", "file" to "a.http")
        assertTrue(error); assertTrue("--max-requests" in text); assertEquals(0, hits.get())
    }

    // ---- rule 8: labelled

    @Test fun responsesAreLabelledAsUntrusted() {
        write("a.http", "GET BASE/echo\n")
        val (text, _) = call(server(), "run", "file" to "a.http", "request" to "1")
        assertTrue("untrusted" in text)
    }

    @Test fun runSelectsByNameOrIndexAndReportsAMissingOne() {
        write("a.http", "### a\n# @name a\nGET BASE/echo\n")
        assertFalse(call(server(), "run", "file" to "a.http", "request" to "a").second)
        assertTrue(call(server(), "run", "file" to "a.http", "request" to "9").second)
        assertTrue(call(server(), "run", "file" to "a.http").second)
        assertNotNull(call(server(), "run_all", "file" to "a.http").first)
    }
}
