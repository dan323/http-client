# Build quality reports

The build-only `quality-reports/` project (not shipped) aggregates reports of all modules except `intellij-plugin`:

- `./gradlew :quality-reports:qualityReports` runs the tests, then writes the aggregated test report (`quality-reports/build/reports/tests/test/aggregated-results/index.html`) and the merged JaCoCo report (`quality-reports/build/reports/jacoco/testCodeCoverageReport/`, HTML plus `testCodeCoverageReport.xml`). `:quality-reports:coverageVerification` fails if merged coverage is below the floor set in `quality-reports/build.gradle.kts` (currently lines 95 %, branches 80 %, measured 95.4 % / 83.6 %). The floor only ever goes up.
- `./gradlew :core:pitest` runs PIT (1.30.2, with the [PIT Kotlin plugin](https://github.com/dan323/pitest-kotlin-plugin): filters for compiler-generated code and four Kotlin mutators, named with PIT's classic default set in the root `build.gradle.kts`) on `core` (about a minute; HTML report in `core/build/reports/pitest/`). Measured mutation score 83.7 % (1436 of 1716 mutants killed, with the Kotlin mutators); the build fails below 80 %. `./gradlew :view-model:pitest` does the same for `view-model` (88 %, 725 of 824, with the Kotlin mutators; floor 85 %).
- The IntelliJ plugin is excluded from JaCoCo (the IDE test runtime instruments classes itself and its tests need an IDE).
