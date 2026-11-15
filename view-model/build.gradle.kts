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
    // A PIT plugin: it is on the classpath of the mutation run only, not of the module.
    pitest(libs.pitest.kotlin)
}

// Mutation testing, run on demand (`./gradlew :view-model:pitest`, report in view-model/build/reports/pitest); same setup as `core`.
pitest {
    pitestVersion.set(libs.versions.pitest.engine.get())
    junit5PluginVersion.set("1.2.1")
    // The Kotlin plugin's filters are on once it is on the classpath; its mutators (elvis, safe call, stdlib swaps) are opt-in.
    mutators.set(listOf("DEFAULTS", "KOTLIN"))
    // The Kotlin plugin's filters are on once it is on the classpath; its mutators (elvis, safe call, stdlib swaps) are opt-in.
    targetClasses.set(listOf("com.dan323.httpclient.viewmodel.*"))
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    threads.set(Runtime.getRuntime().availableProcessors())
    avoidCallsTo.set(listOf("kotlin.jvm.internal", "kotlin.io"))
    // Fails the build below this mutation score (percent). Measured 85.1 % with the Kotlin mutators on (701/824 killed); the floor is rounded down, raise it, never lower it.
    mutationThreshold.set(85)
}
