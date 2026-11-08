---
layout: default
title: Home
---

# HTTP Client

A client for `.http` files (a practical subset of the JetBrains HTTP Client format) with three front ends. They read the same files and give the same results, so pick the one that fits how you work.

| You want to | Use | Guide |
|---|---|---|
| run requests from a terminal, a script or CI, and get JUnit/JSON reports | the command line, `httpc` | [Command line]({{ '/cli/' | relative_url }}) |
| edit a file and click through requests in a window, with no IDE | the desktop app | [Desktop app]({{ '/desktop/' | relative_url }}) |
| run requests next to your code in an IDE | the IntelliJ plugin | [IntelliJ plugin]({{ '/plugin/' | relative_url }}) |

## Install

Download the file for your interface from the [latest release](https://github.com/dan323/http-client/releases/latest):

| Asset | For | Needs |
|---|---|---|
| `httpc-<version>.zip` / `.tar` | the command line | Java 21 or newer |
| `http-client-desktop-<version>-macos.dmg`, `-windows.msi`, `-linux.deb` | the desktop app | nothing |
| `http-client-intellij-plugin-<version>.zip` | IntelliJ IDEA 2026.1 or newer | the IDE |

The installers are not signed, so macOS and Windows may warn on first start.

## Your first request

Create a file called `first.http` with this content:

```http
# @name hello
GET https://httpbin.org/get?user=me
Accept: application/json
```

Open or run it in any of the three front ends (steps in each guide). You get the status line, the response headers and the JSON body.

## Two things every guide builds on

**Several requests in one file** are separated by a line starting with `###`. Give a request a name with `# @name something` on the line above it.

**Variables** are written `{{ "{{name}}" }}` and come from the file (`@host = httpbin.org`), from an environment file called `http-client.env.json` next to your `.http` file, or from dynamic variables such as `$uuid`. An environment file looks like this:

```json
{
  "dev":  { "base": "http://localhost:8080", "user": "me" },
  "prod": { "base": "https://api.example.com", "user": "ci" }
}
```

You then use the variable `base` in a request and choose `dev` or `prod` when you run. Without a choice, only the variables in the file itself are used.

Requests can read earlier responses, and `# @assert` lines check a response. All the syntax is in the [feature reference](https://github.com/dan323/http-client/blob/master/docs/features.md). Ready-made example files, one per feature, are in [samples/](https://github.com/dan323/http-client/tree/master/samples).
