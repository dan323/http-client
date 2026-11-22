# mcp-server

`httpc-mcp`: an MCP server over **stdio** that lets a local coding agent list, analyse and run the requests of `.http` files. The MCP client starts it as a subprocess; it opens no port and nothing is deployed. The rules it enforces are in [docs/mcp-safety-model.md](../docs/mcp-safety-model.md).

```
./gradlew :mcp-server:installDist
mcp-server/build/install/httpc-mcp/bin/httpc-mcp --root /abs/path/to/project
```

Client configuration (for example `.mcp.json`):

```json
{ "mcpServers": { "httpc": { "command": "/abs/path/httpc-mcp", "args": ["--root", "/abs/path/to/project"] } } }
```

## Tools

| Tool | Arguments | Does |
|---|---|---|
| `list_requests` | `file` | names, methods and URLs as written (`{{var}}` unresolved) |
| `list_environments` | `file` | environment names, never values |
| `analyze` | `file`, `environment?` | undefined variables, bad references, parse errors; sends nothing |
| `run` | `file`, `request`, `environment?` | one request (by `@name` or 1-based index) and the ones it chains on |
| `run_all` | `file`, `environment?` | the whole file in order |

## Flags

| Flag | Meaning | Default |
|---|---|---|
| `--root DIR` | folder the tools may read `.http` files from (absolute, repeatable, required) | none |
| `--allow-host H` | where requests may go: a name, `*.suffix`, an IP or a CIDR (repeatable; replaces the default) | loopback |
| `--allow-write` | also run POST, PUT, PATCH, DELETE, without asking | off |
| `--allow-env NAME` | let `{{$env.NAME}}` read that OS variable | none |
| `--max-requests N` | requests per call, redirect hops and chain dependencies included | 100 |
| `--total-timeout D` / `--request-timeout D` | one deadline per call / limit for one request | 5m / 60s |
| `--max-body-bytes N` | a response body is cut here | 65536 |

Logs and the start notice go to stderr; stdout carries only the protocol.
