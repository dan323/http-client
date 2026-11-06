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
