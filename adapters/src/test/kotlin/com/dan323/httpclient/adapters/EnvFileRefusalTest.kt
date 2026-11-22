package com.dan323.httpclient.adapters

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class EnvFileRefusalTest {
    private val dir = Files.createTempDirectory("envrefuse").toRealPath()

    @Test fun refusesBothEnvironmentFilesAndTheirSymlinks() {
        Files.writeString(dir.resolve("http-client.private.env.json"), "{}")
        Files.writeString(dir.resolve("http-client.env.json"), "{}")
        Files.createSymbolicLink(dir.resolve("alias.txt"), dir.resolve("http-client.private.env.json"))
        val reader = LocalFileReader(dir, refuseEnvironmentFiles = true)
        for (name in listOf("http-client.private.env.json", "http-client.env.json", "alias.txt", "./sub/../HTTP-CLIENT.env.json")) {
            assertFailsWith<FileAccessDeniedException>(name) { reader.read(name) }
        }
    }

    @Test fun otherFilesAndTheDefaultStillWork() {
        Files.writeString(dir.resolve("body.txt"), "hi")
        Files.writeString(dir.resolve("http-client.env.json"), "{}")
        assertContentEquals("hi".toByteArray(), LocalFileReader(dir, refuseEnvironmentFiles = true).read("body.txt"))
        assertContentEquals("{}".toByteArray(), LocalFileReader(dir).read("http-client.env.json"))
    }
}
