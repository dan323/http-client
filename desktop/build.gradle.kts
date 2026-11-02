// Standalone desktop front end (Compose Multiplatform). Runs on Windows, macOS and Linux;
// installers must be built on the OS they target (see README.md).
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.12.2"
}

repositories {
    google()
}

dependencies {
    implementation(project(":adapters"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.dan323.httpclient.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "HTTP Client"
            // Installers require MAJOR >= 1, so this is independent of the project version.
            packageVersion = "1.0.0"
            description = "Postman-style client for JetBrains .http files"
            windows { menuGroup = "HTTP Client" }
        }
    }
}
