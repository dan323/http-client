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

intellijPlatform {
    pluginConfiguration {
        name = "HTTP Client"
        version = project.version.toString()
    }
}
