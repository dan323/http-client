# Samples

Manual test files for all front ends. They run against the local mock server in `../mock-server`
(an httpbin-style subset: `/get`, `/post`, `/put`, `/patch`, `/delete`, `/anything`, `/headers`,
`/status/{code}`, `/redirect/{n}`, `/bearer`), so no internet is needed.

1. Start the mock server (port 8080):
   ```
   ./gradlew :mock-server:run
   ```
2. In another terminal, or from the desktop app / IntelliJ plugin, run a sample with the `dev` environment:
   ```
   ./gradlew :cli:run --args="samples/basic.http --env dev"
   ./gradlew :cli:run --args="samples/chaining.http --env dev --request use-token"
   ```

| File | Shows |
|---|---|
| `basic.http` | GET/POST, JSON body, method-less line, headers |
| `variables.http` | environment and dynamic variables; two requests that are expected to fail |
| `chaining.http` | `{{name.response...}}` references, prerequisites, a missing-path failure |
| `assertions.http` | `# @assert` lines; the last request is expected to fail an assertion |
| `file-body.http` | `< ./payload.json` body |
| `methods.http` | PUT, PATCH, DELETE, HEAD, OPTIONS, 404, redirects |

Environments are in `http-client.env.json`: `dev` is the mock server; `httpbin` targets the public
https://httpbin.org instead. For secrets, copy `http-client.private.env.json.example` to
`http-client.private.env.json` (git-ignored). Requests marked "expected to fail" demonstrate the error output.

`SamplesTest` in the `cli` module runs every file against the mock server, so the samples stay working.
