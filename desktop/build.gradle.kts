// Standalone desktop front end (Compose Multiplatform). Runs on Windows, macOS and Linux;
// installers must be built on the OS they target (see README.md).
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

repositories {
    google()
}

dependencies {
    implementation(project(":adapters"))
    implementation(project(":view-model"))
    implementation(libs.kotlinx.serialization.json)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(project(":mock-server"))
}

// JDK modules the installers' trimmed runtime (jlink) needs on top of the plugin's defaults. The Compose plugin does not
// apply what `suggestRuntimeModules` finds, and our dependencies are not modular, so the list is kept by hand and
// `checkRuntimeModules` (part of `check`) fails when jdeps finds a module that the built runtime image does not contain.
// Modules loaded only by reflection or ServiceLoader are invisible to jdeps: add those here too when found.
val extraRuntimeModules = listOf("java.net.http", "jdk.unsupported", "java.instrument")

compose.desktop {
    application {
        mainClass = "com.dan323.httpclient.desktop.MainKt"
        nativeDistributions {
            modules(*extraRuntimeModules.toTypedArray())
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "HTTP Client"
            // Installers require MAJOR >= 1, so this is independent of the project version.
            packageVersion = "1.0.0"
            description = "Postman-style client for .http files (a practical subset of the JetBrains format)"
            windows { menuGroup = "HTTP Client" }
            // Debian package names must be lowercase with no spaces.
            linux { packageName = "http-client" }
        }
    }
}

// Fails when the code (or a dependency) uses a JDK module that the installers' runtime image would not contain.
// Builds the runtime image (jlink, a few seconds) and compares its modules with what jdeps reports for the classpath.
val checkRuntimeModules by tasks.registering {
    group = "verification"
    description = "Checks that the jlink runtime image of the installers contains every JDK module the app needs."
    dependsOn("createRuntimeImage")
    val classpath = configurations.runtimeClasspath.get()
    val appJar = tasks.jar.flatMap { it.archiveFile }
    val imageRelease = layout.buildDirectory.file("compose/tmp/main/runtime/release")
    val jdeps = File(System.getProperty("java.home"), "bin/jdeps")
    doLast {
        val jars = classpath.files.filter { it.name.endsWith(".jar") } + appJar.get().asFile
        val cp = jars.joinToString(File.pathSeparator)
        val process = ProcessBuilder(
            jdeps.path, "--multi-release", "21", "--ignore-missing-deps", "--print-module-deps",
            "--class-path", cp, *jars.map { it.path }.toTypedArray(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "jdeps failed:\n$output" }
        val needed = output.lines().last().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val bundled = imageRelease.get().asFile.readLines()
            .first { it.startsWith("MODULES=") }.substringAfter('=').trim('"').split(' ').toSet()
        val missing = needed - bundled
        check(missing.isEmpty()) {
            "The installers' runtime is missing JDK modules the app needs: ${missing.sorted()}. " +
                "Add them to extraRuntimeModules in desktop/build.gradle.kts."
        }
    }
}

tasks.named("check") { dependsOn(checkRuntimeModules) }
