---
layout: default
title: Command line
permalink: /cli/
---

# Command line

`httpc` runs the requests of a `.http` file and prints each response. It is meant for terminals, scripts and CI: it can write JUnit and JSON reports and its exit code says whether the run passed.

## 1. Install

1. Download `httpc-<version>.zip` (or `.tar`) from the [latest release](https://github.com/dan323/http-client/releases/latest).
2. Unpack it: `unzip httpc-X.Y.Z.zip`.
3. Check that Java 21 or newer is installed: `java -version`.
4. Run `httpc-X.Y.Z/bin/httpc --list first.http` (on Windows `httpc-X.Y.Z\bin\httpc.bat`). Add the `bin` folder to your `PATH` to call `httpc` from anywhere.

## 2. Run a file

1. Save the request from the [home page]({{ '/' | relative_url }}) as `first.http`.
2. List what the file contains: `httpc --list first.http`.
3. Run it: `httpc first.http`.

For each request you see the status line with timing, the response headers and the body. A summary such as `1 requests, 1 passed in 0.4s` goes to the error stream, so standard output holds only response data.

## 3. Use an environment and pick requests

1. Put an `http-client.env.json` next to your file (see the [home page]({{ '/' | relative_url }})).
2. Run with an environment: `httpc api.http --env dev`. Without `--env` no environment is used.
3. Run one request and the requests it depends on: `httpc api.http --env dev --request create-order` (a name, or the 1-based position).
4. Override a variable for one run: `httpc api.http --env dev --var user=alice`.

## 4. Check responses

Add `# @assert` lines under a request. After the response each assertion is printed as `PASS ...` or `FAIL ... -- message`, followed by a total line `Assertions: N passed, M failed`.

## 5. Use it in a script or in CI

1. Write a report: `httpc smoke.http --env staging --report-junit smoke.xml` (or `--report-json`).
2. Fail the build on HTTP errors as well: add `--fail-on-http-error`.
3. Keep the output short: add `--quiet`. Failures and the summary still appear.
4. Save one response body: `httpc api.http --request avatar --output avatar.png`.
5. Read the exit code: `0` everything passed, `1` a request or an assertion failed, `2` wrong arguments, `3` the run passed but a report could not be written.

A complete GitHub Actions job is in [Getting started](https://github.com/dan323/http-client/blob/master/docs/getting-started.md#command-line).

## More

All options, report formats and exit codes: [feature reference, command line](https://github.com/dan323/http-client/blob/master/docs/features.md#command-line).
