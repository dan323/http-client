// Local httpbin-style server so the sample .http files work offline. Not part of any product.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.dan323.httpclient.mock.MainKt")
    applicationName = "mock-server"
}

// Test/sample fixture only: run it with `:mock-server:run`, but never package or release it.
tasks.matching { it.name in setOf("distZip", "distTar", "installDist", "startScripts") }.con    figureEach { enabled = false }
