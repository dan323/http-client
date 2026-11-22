---
layout: default
title: Coding agents (MCP)
permalink: /agents/
---

# Coding agents (MCP)

`httpc-mcp` lets a coding agent (Claude Code, or any client that speaks the Model Context Protocol) list, check and run the requests of your `.http` files. It runs on your machine: the agent's program starts it as a child process and talks to it over standard input and output. It opens no port and nothing is deployed.

The agent is not trusted with more than you allow. By default it can only read `.http` files under the folders you name, send requests to your own machine, and send requests that read data. Everything else is a switch you turn on when you start the server.

## 1. Build it

`httpc-mcp` is not in the release downloads yet, so build it from a clone of the repository. You need Java 21 or newer.

1. Clone the repository and enter it: `git clone https://github.com/dan323/http-client.git && cd http-client`.
2. Build the server: `./gradlew :mcp-server:installDist` (on Windows `gradlew.bat`).
3. The program is `mcp-server/build/install/httpc-mcp/bin/httpc-mcp` (`httpc-mcp.bat` on Windows). Use its full path below.

## 2. Connect your agent

Tell the agent which folder holds your `.http` files. The folder is given with `--root` and must be a full path.

**Claude Code:**

```
claude mcp add httpc -- /full/path/to/httpc-mcp --root /full/path/to/your/project
```

**Other clients**, in the client's MCP settings or a `.mcp.json` file:

```json
{
  "mcpServers": {
    "httpc": {
      "command": "/full/path/to/httpc-mcp",
      "args": ["--root", "/full/path/to/your/project"]
    }
  }
}
```

Give `--root` more than once to allow several folders. The server refuses to start without one.

## 3. What the agent can do

| Tool | What it does |
|---|---|
| `list_requests` | the requests of a file: name, method and URL as written (with variables not filled in) |
| `list_environments` | the environment names for a file, never their values |
| `analyze` | finds undefined variables, bad references and lines that do not parse, and sends nothing |
| `run` | runs one request (by `@name` or position) and the requests it depends on |
| `run_all` | runs the whole file in order |

The agent gives file paths relative to a root, for example `api/orders.http`. Only `.http` and `.rest` files inside a root can be used; an environment file is looked up beside the file or in a parent folder, but never above the root.

Ask in plain words, for example: *"Check `api/orders.http` and run the `create-order` request against the `dev` environment."*

A result lists, for each request, the status and the assertions that passed or failed. What came from the server (headers and body) is grouped under `remote` and marked `untrusted`, because a response is data from someone else's server and may contain text that tries to give the agent instructions. A body is cut at 64 KB and says so when it is.

## 4. What stays hidden

- **Secrets.** Values that the request uses (from your environment files, from `$env` variables and from earlier responses) are replaced by their variable name, for example `{{ "{{token}}" }}`, in the body, the headers and the error messages, even when the server echoes them back. This is best effort: a value the server changed (hashed, split or encoded another way) cannot be recognised.
- **Environment files.** A request body can never be read from `http-client.env.json` or `http-client.private.env.json`.
- **Your files and machine.** Requests with `# @proxy` are refused. Nothing is written: a `>>` or `>>!` line in a file saves nothing (the request still runs, and its result carries a `saveError` saying that saving is not available here), and a `<>` line keeps no link (the request runs and the result carries no error). `$env` is empty unless you pass `--allow-env`.

## 5. Allow more

Each switch is part of the command that starts the server, so the agent cannot turn any of them on.

| Flag | Allows | Default |
|---|---|---|
| `--allow-host HOST` | requests to a host: a name, `*.example.com` (any depth below it, not the bare domain), an IP address or a range such as `10.0.0.0/8`. Repeat it for several. Naming a host replaces the default. | `localhost` and the loopback addresses |
| `--allow-write` | `POST`, `PUT`, `PATCH` and `DELETE` | only `GET`, `HEAD` and `OPTIONS` |
| `--allow-unconfirmed-write` | with `--allow-write`, sending them even when the client cannot ask you first | off |
| `--allow-env NAME` | `{{ "{{$env.NAME}}" }}` to read that variable from the process environment (repeatable) | none |
| `--max-requests N` | requests in one call, redirects and requests a request depends on included | 100 |
| `--total-timeout`, `--request-timeout` | time for one call, and for one request (for example `5m`, `60s`) | 5m, 60s |
| `--max-body-bytes N` | how much of a response body is returned | 65536 |

A name does not prove where a request goes (a name such as `x.nip.io` can lead anywhere), so the server looks up every name itself and refuses addresses on your own network (private, link-local and similar ranges) unless you list that address or range. A redirect is checked like a request: it cannot lead to a host you did not allow.

### Writes ask you first

With `--allow-write`, a client that supports MCP *elicitation* shows you a question before any request that changes data is sent. It lists each such request (method and URL as written, with no body and no values). Anything but an explicit yes, including closing the question, sends nothing, not even the reads that came before it in the same call.

A client that cannot ask would leave the server with no way to get your answer, so there the call is refused. If you accept that, add `--allow-unconfirmed-write` and those requests run without asking; the server then says so on its error stream when it starts.

## 6. When a request is refused

The result is marked as an error and says why. The usual ones:

| Message | What to do |
|---|---|
| `No such .http or .rest file under the allowed roots` | the path is wrong, or outside every `--root` (the server answers the same for a missing file and a file it may not use) |
| `'host' is not an allowed host (allowed hosts are set with --allow-host)` | add `--allow-host` for it, or check the URL (the check also applies to a redirect) |
| `... changes data (allow it with --allow-write)` | start the server with `--allow-write` if the agent should send it |
| `the client cannot ask the user to confirm ...` | the client does not support elicitation; see above |
| `This call would send N requests; the limit is M (--max-requests)` | the call needs more requests than allowed, chained ones included; split the file or raise `--max-requests` |
| `more than N requests in one call` | the limit was reached while sending (redirects count too); raise `--max-requests` or check where the redirects lead |
| `the user did not allow these requests to be sent` | you declined the question (or closed it); nothing was sent |
| `the host of ... depends on an earlier response, and an earlier request changes data` | a later request would take its host from a response after a write; write the host in the file |
| `# @proxy is not allowed here` | remove the setting from the file |

## More

The rules and the reasoning behind them are in the [safety model](https://github.com/dan323/http-client/blob/master/docs/mcp-safety-model.md); the flags are also listed in the [server's README](https://github.com/dan323/http-client/blob/master/mcp-server/README.md). To teach an agent the `.http` format itself, install the [agent skill](https://github.com/dan323/http-client/blob/master/agent-skill/README.md); the skill and the server work together.
