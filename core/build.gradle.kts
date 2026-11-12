// The hexagon: domain + application + ports. No third-party runtime dependencies.
plugins {
    kotlin("jvm")
    alias(libs.plugins.pitest)
}

dependencies {
    testImplementation(kotlin("test"))
}

// Mutation testing, run on demand (`./gradlew :core:pitest`, report in core/build/reports/pitest); see
// "Build quality reports" in the README. `view-model` is mutated too (see its build file).
pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(listOf("com.dan323.httpclient.core.*"))
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    threads.set(Runtime.getRuntime().availableProcessors())
    // Kotlin generates a lot of non-logic bytecode (null checks, when/default stubs); mutating it only adds noise.
    avoidCallsTo.set(listOf("kotlin.jvm.internal", "kotlin.io"))
    // Fails the build below this mutation score (percent). Measured 83 % on the first run (820/988 killed); the floor is rounded down, raise it, never lower it.
    mutationThreshold.set(80)
}
