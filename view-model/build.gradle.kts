// Pure, UI-toolkit-free view logic shared by the desktop app and the IntelliJ plugin
// (JSON tree/spans, status and size/duration formatting). No Compose, Swing or IDE classes.
// kotlinx-serialization is the same copy the IDE provides; the plugin build excludes it from its zip.
plugins {
    kotlin("jvm")
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
}
