// Build-only project (not shipped): aggregates JUnit and JaCoCo reports of all modules.
// Output: quality-reports/build/reports/** (see "Build quality reports" in the README).
// The IntelliJ plugin module is deliberately excluded: its tests need an IDE and are not run in CI.
plugins {
    base
    `test-report-aggregation`
    `jacoco-report-aggregation`
}

// The aggregation resolves each module's classpath, including the Compose artifacts that live on Google's repository.
repositories { google() }

val measured = listOf(":core", ":adapters", ":view-model", ":cli", ":desktop", ":mock-server")

dependencies {
    measured.forEach {
        testReportAggregation(project(it))
        jacocoAggregation(project(it))
    }
}

// Coverage floor on the merged report: the measured level when it was introduced; raise it, never lower it.
// Values are fractions (0.80 = 80 %). Measured on introduction: lines 95.4 %, branches 83.6 % (floors rounded down).
val minLineCoverage = 0.95
val minBranchCoverage = 0.83

reporting {
    reports {
        val testAggregateTestReport by registering(AggregateTestReport::class) {
            testSuiteName = "test"
        }
        val testCodeCoverageReport by registering(JacocoCoverageReport::class) {
            testSuiteName = "test"
        }
    }
}

val coverageVerification = tasks.register<JacocoCoverageVerification>("coverageVerification") {
    group = "verification"
    description = "Fails when merged line/branch coverage drops below the recorded floor."
    val report = tasks.named<JacocoReport>("testCodeCoverageReport")
    dependsOn(report)
    executionData.from(report.map { it.executionData })
    classDirectories.from(report.map { it.classDirectories })
    sourceDirectories.from(report.map { it.sourceDirectories })
    violationRules {
        rule {
            limit { counter = "LINE"; minimum = minLineCoverage.toBigDecimal() }
            limit { counter = "BRANCH"; minimum = minBranchCoverage.toBigDecimal() }
        }
    }
}

tasks.register("qualityReports") {
    group = "verification"
    description = "Aggregated test report + merged JaCoCo report + coverage floor check."
    dependsOn("testAggregateTestReport", "testCodeCoverageReport", coverageVerification)
}
