package com.dan323.httpclient.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvironmentResolutionTest {
    private val root = Files.createTempDirectory("cli-env")
    private fun cli(vararg args: String): Triple<Int, String, String> {
        val o = ByteArrayOutputStream(); val e = ByteArrayOutputStream()
        val code = run(args.toList(), PrintStream(o), PrintStream(e))
        return Triple(code, o.toString(), e.toString())
    }

    @Test fun findsEnvFileInParentFolder() {
        Files.writeString(root.resolve("http-client.env.json"), """{"dev":{"h":"http://127.0.0.1:1"}}""")
        val sub = Files.createDirectories(root.resolve("a/b"))
        val f = sub.resolve("x.http").also { Files.writeString(it, "GET {{h}}/x\n") }
        // the unknown-name message lists "dev", so the parent's file was found
        val (code, _, err) = cli(f.toString(), "--env", "nope")
        assertEquals(1, code)
        assertTrue(err.contains("Unknown environment: nope") && err.contains("dev"), err)
    }

    @Test fun malformedEnvFileNamesTheFile() {
        val env = root.resolve("http-client.env.json").also { Files.writeString(it, "{oops") }
        val f = root.resolve("x.http").also { Files.writeString(it, "GET http://127.0.0.1:1/\n") }
        val (code, _, err) = cli(f.toString(), "--env", "dev")
        assertEquals(1, code)
        assertTrue(err.contains(env.toString()) && !err.contains("Unexpected error"), err)
    }

    @Test fun systemPropertyAllowlistIsHonouredByTheCli() {
        val f = root.resolve("x.http").also { Files.writeString(it, "GET http://127.0.0.1:1/{{\$env.HTTPC_R6_PROBE}}\n") }
        val before = cli(f.toString()).third
        assertTrue(before.contains("not allowed"), before)
        System.setProperty("httpclient.allowEnv", "HTTPC_R6_PROBE")
        try {
            val after = cli(f.toString()).third
            assertTrue(!after.contains("not allowed"), after)   // now allowed (and merely unset)
        } finally {
            System.clearProperty("httpclient.allowEnv")
        }
    }

    @Test fun noEnvOptionUsesFileVariablesOnly() {
        Files.writeString(root.resolve("http-client.env.json"), """{"dev":{"h":"http://127.0.0.1:1"}}""")
        val f = root.resolve("x.http").also { Files.writeString(it, "GET {{h}}/x\n") }
        val (code, _, err) = cli(f.toString())
        assertEquals(1, code)
        assertTrue(err.contains("h"), err)
    }
}
