---
layout: default
title: IntelliJ plugin
permalink: /plugin/
---

# IntelliJ plugin

Adds `.http` files to IntelliJ IDEA (Community or Ultimate) and other IntelliJ-based IDEs: highlighting, a run icon on each request, an environment picker and an **HTTP Response** tool window.

## 1. Install

1. Download `http-client-intellij-plugin-<version>.zip` from the [latest release](https://github.com/dan323/http-client/releases/latest). Do not unpack it.
2. In the IDE open **Settings > Plugins**, click the gear icon and choose **Install Plugin from Disk…**, then pick the zip.
3. Restart the IDE.
4. **IntelliJ IDEA Ultimate only:** also disable its bundled **HTTP Client** plugin (**Settings > Plugins > Installed**). Both plugins claim `.http` files, so keep only one.

The plugin needs IDE version 2026.1 or newer. It has been tried by hand on IntelliJ IDEA Ultimate 2026.3.

## 2. Run a request

1. Open or create a file ending in `.http`. Methods, URLs, headers, variables and comments are highlighted.
2. Click the green run icon in the gutter next to a request. The icon runs its own request wherever the caret is.
3. Or put the caret in a request, right-click and choose **Run HTTP Request**. The shortcut is **Ctrl+Alt+R**.
4. Before the first run the **HTTP Response** tool window explains these ways to run a request. After a run it opens at the bottom with the status, headers and body. JSON bodies are pretty-printed. If the file has `# @assert` lines, their results are listed too.

The file is saved before the request runs. Only one request runs at a time; starting another shows a balloon and does nothing.

## 3. Choose an environment

1. At the top of every `.http` editor there is an environment picker. Click it and choose `dev`, `prod`… (the names in the nearest `http-client.env.json`) or `(none)`.
2. Or right-click in the editor and choose **Select HTTP Environment**.

The choice starts as `(none)` after every IDE restart and applies to runs from every file. A request that uses a variable such as `base` fails with an undefined-variable error until you pick an environment that defines it.

## 4. Requests that depend on each other

A request that uses {% raw %}`{{login.response.body.token}}`{% endraw %} runs the `login` request first. The plugin remembers the responses so the login is not sent again. Editing a file, or deleting or moving it, drops what was remembered for it. Press **Reset chain** in the tool window to drop everything and run the chain again.

## 5. Stop a running request

While a request runs, press **Cancel** in the **HTTP Response** tool window (or the stop icon in its title bar). The window shows **Cancelled** above the responses that had already arrived. The next run sends the whole chain again.

## More

Behavior and limits: [feature reference, IntelliJ plugin](https://github.com/dan323/http-client/blob/master/docs/features.md#intellij-plugin) and the [plugin README](https://github.com/dan323/http-client/blob/master/intellij-plugin/README.md).
