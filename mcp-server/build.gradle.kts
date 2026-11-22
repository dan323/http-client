// MCP server over stdio: lets a local coding agent list, analyse and run requests under the limits of docs/mcp-safety-model.md.
// Started by the MCP client as a subprocess; it opens no port.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":adapters"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.dan323.httpclient.mcp.MainKt")
    applicationName = "httpc-mcp"
}
