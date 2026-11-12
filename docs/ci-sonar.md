# SonarCloud analysis in CI

SonarCloud analyses the code and shows coverage on every pull request. The analysis runs in the `quality` job of
[ci.yml](../.github/workflows/ci.yml), right after `:quality-reports:qualityReports` has written the merged JaCoCo
report, and runs `./gradlew sonar` with the Sonar Gradle plugin, configured in the `sonar` block of [build.gradle.kts](../build.gradle.kts) (the plugin finds the sources, classes and libraries of each module itself).

Why not SonarCloud's "Automatic Analysis": it analyses the sources without running the build, so it has no coverage
data. The two modes cannot be active together; Automatic Analysis must be off for the project.

One-time setup (repository owner):

1. SonarCloud, project *Administration > Analysis Method*: switch **Automatic Analysis** off.
2. SonarCloud, *My Account > Security*: generate a token.
3. GitHub, *Settings > Secrets and variables > Actions*: add the repository secret `SONAR_TOKEN` with that token.

Until the secret exists the analysis step is skipped, so nothing else changes. Pull requests from forks have no
access to secrets and also skip it. Documentation-only pull requests skip the whole job's steps, as for the tests.

`intellij-plugin/**` is excluded from coverage only (`sonar.coverage.exclusions` in the root `build.gradle.kts`): the module is not in the merged JaCoCo report because its tests need an IDE, so editing plugin code must not be blocked by a coverage gate. Bug, smell and security analysis still covers it.

## Mutation testing (PIT)

SonarCloud has no mutation-score metric and does not accept third-party plugins, so the mutants that survived are
imported as external issues. `pitest` (in `core` and `view-model`) is followed by `pitestSonarReport` (registered for every module with the PIT plugin in the
root [build.gradle.kts](../build.gradle.kts)), which converts `<module>/build/reports/pitest/mutations.xml` into
`<module>/build/reports/pitest/sonar-issues.json` (Sonar generic issue format): one `MAJOR` code smell per `SURVIVED` mutant
(rule `pitest:survived-mutant`), on the mutated line. `NO_COVERAGE` mutants are not imported: uncovered code is already reported through JaCoCo. The root `sonar` block passes the files that exist as
`sonar.externalIssuesReportPaths`.

The daily `mutation` job of [ci.yml](../.github/workflows/ci.yml) runs PIT on both modules, then the quality reports and `./gradlew sonar`, so the
analysis carries both coverage and mutants. External issues belong to one analysis: the next push to `master` (which has no PIT report) replaces
them, so they show up in SonarCloud until the next daily run. Locally, `./gradlew :core:pitest :view-model:pitest sonar` imports them
too if you have a token.
