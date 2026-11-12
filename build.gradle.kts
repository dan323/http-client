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
        // The plugin is not in the merged JaCoCo report (its tests need an IDE), so it is excluded from coverage
        // only; analysis for bugs and smells still covers it.
        property("sonar.coverage.exclusions", "intellij-plugin/**")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            layout.projectDirectory.file("quality-reports/build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml").asFile.absolutePath,
        )
        // Surviving PIT mutants as external issues (written by `pitest` in each module that has it, see docs/ci-sonar.md).
        // Only the reports that exist are listed, so the regular `quality` analysis does not warn about a missing one.
        val mutants = subprojects.map { it.layout.projectDirectory.file("build/reports/pitest/sonar-issues.json").asFile }.filter { it.isFile }
        if (mutants.isNotEmpty()) property("sonar.externalIssuesReportPaths", mutants.joinToString(",") { it.absolutePath })
    }
}

allprojects {
    group = "com.dan323.httpclient"
    version = "0.5.0"
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
    // SonarCloud has no mutation metric, so the mutants that survived (covered by tests that still pass) are exported
    // as Sonar "external issues" (generic issue format) that the `sonar` block above imports. See docs/ci-sonar.md.
    plugins.withId("info.solidsoft.pitest") {
        tasks.register("pitestSonarReport") {
            description = "Converts the PIT XML report into a SonarCloud generic external issues file."
            val mutationsXml = layout.buildDirectory.file("reports/pitest/mutations.xml")
            val output = layout.buildDirectory.file("reports/pitest/sonar-issues.json")
            val sourceRoot = layout.projectDirectory.dir("src/main/kotlin")
            val repoRoot = rootProject.layout.projectDirectory
            inputs.file(mutationsXml)
            outputs.file(output)
            doLast {
                fun json(text: String) = buildString {
                    append('"')
                    for (c in text) when {
                        c == '"' -> append("\\\"")
                        c == '\\' -> append("\\\\")
                        c < ' ' -> append("\\u%04x".format(c.code))
                        else -> append(c)
                    }
                    append('"')
                }
                val mutations = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(mutationsXml.get().asFile).getElementsByTagName("mutation")
                val issues = mutableListOf<String>()
                for (i in 0 until mutations.length) {
                    val mutation = mutations.item(i) as org.w3c.dom.Element
                    val status = mutation.getAttribute("status")
                    // NO_COVERAGE mutants are left out: SonarCloud already reports uncovered code through JaCoCo.
                    if (status != "SURVIVED") continue
                    fun text(tag: String) = mutation.getElementsByTagName(tag).item(0).textContent
                    val packagePath = text("mutatedClass").substringBeforeLast('.', "").replace('.', '/')
                    val file = sourceRoot.file("$packagePath/${text("sourceFile")}").asFile
                    if (!file.isFile) continue
                    val path = file.relativeTo(repoRoot.asFile).invariantSeparatorsPath
                    val message = "Mutant survived: ${text("description")} in ${text("mutatedMethod")} (no test fails)"
                    issues += """{"engineId":"pitest","ruleId":"survived-mutant","severity":"MAJOR","type":"CODE_SMELL",""" +
                        """"primaryLocation":{"message":${json(message)},"filePath":${json(path)},"textRange":{"startLine":${text("lineNumber")}}}}"""
                }
                output.get().asFile.writeText("""{"issues":[${issues.joinToString(",")}]}""")
                logger.lifecycle("PIT -> Sonar: ${issues.size} mutant issues in ${output.get().asFile}")
            }
        }
        // Always convert after a PIT run.
        tasks.named("pitest") { finalizedBy("pitestSonarReport") }
    }
}
