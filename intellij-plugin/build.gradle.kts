import java.util.zip.ZipFile

// IntelliJ front end. Compiles against a locally installed IDE (-PideaPath=...) and only depends on
// the platform module, so the built plugin also loads in Community editions.
plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

repositories {
    intellijPlatform { defaultRepositories() }
}

dependencies {
    implementation(project(":adapters"))
    implementation(project(":view-model"))
    testImplementation(kotlin("test"))
    intellijPlatform {
        local(providers.gradleProperty("ideaPath"))
    }
}

// The IDE ships its own kotlinx-serialization (and Kotlin stdlib); bundling a second copy in the plugin
// distribution risks class conflicts, so the plugin uses the IDE's.
configurations.named("runtimeClasspath") {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core")
}

// Guards the exclusions above: fails if kotlinx-serialization reaches the runtime classpath or the built zip.
val verifyNoBundledSerialization by tasks.registering {
    group = "verification"
    description = "Fails if kotlinx-serialization is bundled in the plugin runtime classpath or distribution zip."
    val runtimeFiles = configurations.named("runtimeClasspath")
    val zips = layout.buildDirectory.dir("distributions").map { d -> d.asFileTree.matching { include("*.zip") } }
    doLast {
        val bad = runtimeFiles.get().resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id }
            .filter { it.name.startsWith("kotlinx-serialization") }
        check(bad.isEmpty()) { "kotlinx-serialization on plugin runtimeClasspath: $bad" }
        zips.get().files.forEach { zip ->
            val entries = ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
            val offending = entries.filter { it.substringAfterLast('/').startsWith("kotlinx-serialization") }
            check(offending.isEmpty()) { "kotlinx-serialization bundled in ${zip.name}: $offending" }
        }
    }
}
tasks.named("check") { dependsOn(verifyNoBundledSerialization) }
tasks.matching { it.name == "buildPlugin" }.configureEach { finalizedBy(verifyNoBundledSerialization) }

intellijPlatform {
    pluginConfiguration {
        name = "HTTP Client"
        version = project.version.toString()
    }
}
