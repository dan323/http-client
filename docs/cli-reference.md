# Command line reference

Every option of `httpc`. For a first run, see the [command line guide](guide/cli.md).

```
./gradlew :cli:run --args="requests.http --env dev --request login"
```

- `--env NAME` — use variables from `NAME` in the nearest `http-client.env.json` (searching upward from the file; see [features](features.md#environments)). Without it no environment is used.
- `--request X` — run only the request with `# @name X`, or the Nth request (1-based), plus any requests it chains on.
- `--allow-env NAME[,NAME]` — let `{{$env.NAME}}` read these OS variables (none by default).
- `--allow-file-root DIR` — also let `< path` bodies be read from `DIR` (by default only the `.http` file's folder).
- `--list` — list the requests in the file and exit.
- `--check` — look for problems (undefined variables, bad references, lines that do not parse) without sending anything; one `FILE:LINE:COLUMN: error|warning: message [code]` per line, exit `1` on an error. Only `--env` and `--var` may be added.
- `--report-json FILE` / `--report-junit FILE` — also write a run report (per-request status, timings, assertion results, totals) as JSON or JUnit XML; console output is unchanged; a report path must differ from the input file (see [features](features.md#run-reports)).

- `--quiet` — no per-request output on stdout; failed assertions and errors still go to stderr.
- `--fail-on-http-error` — exit `1` when a selected request gets a 4xx/5xx response, even if its assertions pass (printed to stderr, also with `--quiet`; prerequisites run only for chaining are not judged). Without it an HTTP error status does not fail the run.
- `--output FILE` — write the raw response body (byte for byte, so binaries work; text keeps its original charset; gzip/br bodies stay compressed, as received) of the single selected request to `FILE` (needs `--request` when the file holds several requests; owner-only file).
- `--var NAME=VALUE` — set a variable (repeatable); it overrides file and environment variables.
- `--timeout DURATION` — total time limit for requests without `# @timeout` (`30`, `500ms`, `45s`, `2m`; default 60 s).

After a run a summary line such as `3 requests, 2 passed, 1 failed in 1.2s` is printed to stderr.

Exit codes: `0` success, `1` a request, assertion or setup failed, `2` bad arguments, `3` the run passed but a report or output file could not be written.

`# @assert status == 200` lines are checked after each response; the CLI prints PASS/FAIL and exits 1 on any failure (see [features](features.md#assertions)).

`./gradlew :cli:installDist` builds a standalone `httpc` launcher in `cli/build/install/httpc/bin`; `./gradlew :cli:distZip` (or `distTar`) builds the downloadable distribution that releases attach. Install steps for release assets: [getting started](getting-started.md).
