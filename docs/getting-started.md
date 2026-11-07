# Getting started

How to install the client, send a first request, use environments and run a `.http` file in CI.
Full syntax is in [features.md](features.md); differences from the JetBrains client are in [jetbrains-compatibility.md](jetbrains-compatibility.md).

## 1. Install

Every tagged release (`v*`) has these files on the project's GitHub Releases page; the [release workflow](../.github/workflows/release.yml) builds and attaches them.

| Asset | For | Needs |
|---|---|---|
| `httpc-<version>.zip` / `.tar` | the command line (`httpc`) | Java 21 or newer on `PATH` (or `JAVA_HOME`) |
| `http-client-intellij-plugin-<version>.zip` | IntelliJ IDEA (Community or Ultimate) and other IntelliJ-based IDEs | the IDE |
| `http-client-desktop-<version>-macos.dmg`, `-windows.msi`, `-linux.deb` | the desktop app | nothing (the installer bundles a runtime) |

The desktop installers are built on a best-effort basis: if one is missing from a release, its build failed on that OS (see the workflow run) and you can build it yourself, see [desktop/README.md](../desktop/README.md). The installers are not signed, so macOS and Windows may warn on first start.

### Command line

```
unzip httpc-X.Y.Z.zip            # or: tar xf httpc-X.Y.Z.tar
httpc-X.Y.Z/bin/httpc --list my-requests.http      # Windows: httpc-X.Y.Z\bin\httpc.bat
```

Add `httpc-<version>/bin` to your `PATH` to call `httpc` from anywhere. Nothing else is installed or changed.

### IntelliJ plugin

*Settings > Plugins > gear icon > Install Plugin from Disk...*, pick the plugin zip and restart the IDE. Details and limits (the plugin is not yet verified in a real IDE): [intellij-plugin/README.md](../intellij-plugin/README.md).

### Desktop app

Run the installer for your OS and start "HTTP Client". Use **Open...** to pick a `.http` file. See [desktop/README.md](../desktop/README.md).

### From source

`./gradlew :cli:installDist` builds the same launcher in `cli/build/install/httpc/bin`; `./gradlew :cli:distZip` builds the zip.

## 2. Your first request

Create `hello.http`:

```
# @name hello
# @assert status == 200
GET https://httpbin.org/get?greeting=hi
Accept: application/json
```

Run it:

```
httpc hello.http
```

The CLI prints the response and one PASS or FAIL line per `# @assert`; it exits `0` when everything passed and `1` otherwise. Several requests live in one file, separated by a line starting with `###`. Useful options: `--list` shows the requests, `--request hello` runs one (and the requests it chains on).

To try things without internet, start the bundled mock server from a checkout (`./gradlew :mock-server:run`, port 8080) and run the files in [samples/](../samples/README.md) with `--env dev`.

## 3. Variables and environment files

Put `{{name}}` in a URL, header or body. Define values in the file:

```
@base = https://httpbin.org

GET {{base}}/get
```

or, to switch between targets, in a `http-client.env.json` next to the file (or in any parent folder; the nearest one wins):

```json
{
  "local":   { "base": "http://localhost:8080" },
  "staging": { "base": "https://httpbin.org" }
}
```

```
httpc requests.http --env staging
```

Without `--env` no environment is read. Secrets go in `http-client.private.env.json` beside it (same shape, merged over the first; keep it out of Git, this repository's `.gitignore` does). Other sources of values: `--var NAME=VALUE` on the command line, [dynamic variables](features.md#dynamic-variables) such as `{{$uuid}}`, values from earlier responses (`{{login.response.body.$.token}}`, see [chaining](features.md#request-chaining)) and, only when you allow them, OS variables with `--allow-env NAME` and `{{$env.NAME}}`.

## 4. Running in CI

`httpc` has no runtime dependency except Java 21, and its exit codes and reports are meant for CI:

| Option | Effect |
|---|---|
| `--fail-on-http-error` | exit `1` also for a 4xx/5xx response whose assertions pass |
| `--report-junit FILE` | JUnit XML, read by most CI systems |
| `--report-json FILE` | the same data as JSON |
| `--quiet` | no per-request output (failures still go to stderr) |
| `--timeout 30s` | total limit for requests without `# @timeout` (default 60 s) |

Exit codes: `0` success, `1` a request, assertion or setup failed, `2` bad arguments, `3` the run passed but a report could not be written.

Example GitHub Actions job using the release asset:

```yaml
jobs:
  api-smoke-test:
    runs-on: ubuntu-latest
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
          HTTPC_VERSION: X.Y.Z   # a release that has the httpc-*.zip asset
      - name: Run
        run: httpc-$HTTPC_VERSION/bin/httpc examples/smoke-test.http --env staging --fail-on-http-error --report-junit smoke.xml
        env:
          HTTPC_TIMEOUT: 30s
      - uses: actions/upload-artifact@v7
        if: always()
        with: { name: smoke-report, path: smoke.xml }
```

Reports and the console output can contain secrets (tokens in query strings): treat the artifacts as sensitive. A ready-made file to start from is [examples/smoke-test.http](../examples/smoke-test.http).

## 5. Examples

- [examples/smoke-test.http](../examples/smoke-test.http) and [examples/http-client.env.json](../examples/http-client.env.json): a CI smoke test with assertions and chaining.
- [samples/](../samples/README.md): one file per feature (variables, chaining, assertions, dynamic variables, file bodies, methods), all run against the mock server by `SamplesTest`.

## Not done yet (follow-up options)

Releases only attach files to GitHub; nothing is published to other channels. Possible next steps, each needing an account or a decision by the maintainer:

- Homebrew tap or cask, Scoop bucket, winget manifest for the CLI and the desktop app.
- JetBrains Marketplace listing for the plugin (needs a vendor account, a signing certificate and a Marketplace token; the plugin should first be verified in a real IDE, plan task T21).
- Code signing and notarization of the macOS and Windows installers.
- A GitHub Action wrapper (`uses: dan323/http-client@v1`) that downloads `httpc`.
- An SDKMAN or Docker image of the CLI.
