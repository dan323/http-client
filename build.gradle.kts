plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "com.dan323.httpclient"
    version = "0.5.0-rc1"
    repositories { mavenCentral() }
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(21) }
        tasks.withType<Test> { useJUnitPlatform() }
        // Coverage for the merged report in :quality-reports. Not applied to :intellij-plugin: the IDE test
        // runtime does its own class instrumentation and the plugin's tests are not run in CI.
        if (path != ":intellij-plugin") apply(plugin = "jacoco")
    }
}
