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
