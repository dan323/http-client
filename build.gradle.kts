plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.sonarqube)
}

// SonarCloud analysis (`./gradlew sonar`, run by the `quality` job after :quality-reports:qualityReports; needs the
// SONAR_TOKEN environment variable). The plugin collects sources, tests, classes and libraries of every module itself;
// coverage comes from the merged JaCoCo report. See docs/ci-sonar.md.
sonar {
    properties {
        property("sonar.projectKey", "dan323_http-client")
        property("sonar.organization", "dan323")
        property("sonar.host.url", "https://sonarcloud.io")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            layout.projectDirectory.file("quality-reports/build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml").asFile.absolutePath,
        )
    }
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
