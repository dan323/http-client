// Pure, UI-toolkit-free view logic shared by the desktop app and the IntelliJ plugin
// (JSON tree/spans, status and size/duration formatting). No Compose, Swing or IDE classes.
// kotlinx-serialization is the same copy the IDE provides; the plugin build excludes it from its zip.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.pitest)
}

dependencies {
    api(project(":core"))
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

// Mutation testing, run on demand (`./gradlew :view-model:pitest`, report in view-model/build/reports/pitest); same setup as `core`.
pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(listOf("com.dan323.httpclient.viewmodel.*"))
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    threads.set(Runtime.getRuntime().availableProcessors())
    avoidCallsTo.set(listOf("kotlin.jvm.internal", "kotlin.io"))
    // Fails the build below this mutation score (percent). Measured 86 % on the first run (506/587 killed); the floor is rounded down, raise it, never lower it.
    mutationThreshold.set(85)
}
