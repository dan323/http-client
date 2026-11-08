# Getting started

How to install each piece of the client. What you can write in a `.http` file and what each front end does is in [features.md](features.md); differences from the JetBrains client are in [jetbrains-compatibility.md](jetbrains-compatibility.md).

Every tagged release (`v*`) has these files on the project's GitHub Releases page; the [release workflow](../.github/workflows/release.yml) builds and attaches them.

| Asset | For | Needs |
|---|---|---|
| `httpc-<version>.zip` / `.tar` | the command line (`httpc`) | Java 21 or newer on `PATH` (or `JAVA_HOME`) |
| `http-client-intellij-plugin-<version>.zip` | IntelliJ IDEA (Community or Ultimate) and other IntelliJ-based IDEs | the IDE |
| `http-client-desktop-<version>-macos.dmg`, `-windows.msi`, `-linux.deb` | the desktop app | nothing (the installer bundles a runtime) |

## Command line

```
unzip httpc-X.Y.Z.zip            # or: tar xf httpc-X.Y.Z.tar
httpc-X.Y.Z/bin/httpc --list my-requests.http      # Windows: httpc-X.Y.Z\bin\httpc.bat
```

Add `httpc-<version>/bin` to your `PATH` to call `httpc` from anywhere. Nothing else is installed or changed. Options, exit codes and reports: [Command line](features.md#command-line) and [Run reports](features.md#run-reports).

**In GitHub Actions**, download the asset of a release that has it and run it (replace `X.Y.Z`):

```yaml
jobs:
  api-smoke-test:
    runs-on: ubuntu-latest
    env:
      HTTPC_VERSION: X.Y.Z   # a release that has the httpc-*.zip asset
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with: { distribution: temurin, java-version: 21 }
      - name: Install httpc
        run: |
          gh release download "v$HTTPC_VERSION" --repo dan323/http-client --pattern 'httpc-*.zip'
          unzip -q httpc-*.zip
        env:
          GH_TOKEN: ${{ github.token }}
      - name: Run
        run: httpc-$HTTPC_VERSION/bin/httpc examples/smoke-test.http --env staging --fail-on-http-error --report-junit smoke.xml
```

`--fail-on-http-error` and `--report-junit FILE` are described in [Command line](features.md#command-line). A ready-made file to run is [examples/smoke-test.http](../examples/smoke-test.http) with [examples/http-client.env.json](../examples/http-client.env.json); more files, one per feature, are in [samples/](../samples/README.md).

## IntelliJ plugin

*Settings > Plugins > gear icon > Install Plugin from Disk...*, pick the plugin zip and restart the IDE. Usage: [IntelliJ plugin](features.md#intellij-plugin). It needs IntelliJ IDEA 2026.1 or newer (build 261+), and the "HTTP Client" plugin bundled with Ultimate should be disabled. Details and limits: [intellij-plugin/README.md](../intellij-plugin/README.md).

## Desktop app

Run the installer for your OS and start "HTTP Client". The installers are built on a best-effort basis: if one is missing from a release, its build failed on that OS (see the workflow run) and you can build it yourself, see [desktop/README.md](../desktop/README.md). They are not signed, so macOS and Windows may warn on first start. Usage: [Desktop app](features.md#desktop-app).

## From source

`./gradlew :cli:installDist` builds the same launcher in `cli/build/install/httpc/bin`; `./gradlew :cli:distZip` builds the zip; `./gradlew :intellij-plugin:buildPlugin` builds the plugin zip.

## Not done yet (follow-up options)

Releases only attach files to GitHub; nothing is published to other channels. Possible next steps, each needing an account or a decision by the maintainer:

- Homebrew tap or cask, Scoop bucket, winget manifest for the CLI and the desktop app.
- JetBrains Marketplace listing for the plugin (needs a vendor account, a signing certificate and a Marketplace token; the plugin should first be verified in a Community install and on the 2026.1 baseline, plan task T21).
- Code signing and notarization of the macOS and Windows installers.
- A GitHub Action wrapper (`uses: dan323/http-client@v1`) that downloads `httpc`.
- An SDKMAN or Docker image of the CLI.
