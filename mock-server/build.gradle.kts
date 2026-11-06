// Local httpbin-style server so the sample .http files work offline. Not part of any product.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.dan323.httpclient.mock.MainKt")
    applicationName = "mock-server"
}

// Test/sample fixture only: run it with `:mock-server:run`, but never package or release it.
tasks.matching { it.name in setOf("distZip", "distTar", "installDist", "startScripts") }.configureEach { enabled = false }
