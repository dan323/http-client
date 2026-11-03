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

intellijPlatform {
    pluginConfiguration {
        name = "HTTP Client"
        version = project.version.toString()
    }
}
