---
layout: default
title: Desktop app
permalink: /desktop/
---

# Desktop app

A window for Windows, macOS and Linux with a file editor, a list of the requests in the file, an environment picker and a response viewer. It needs no IDE.

## 1. Install

1. Download the installer for your system from the [latest release](https://github.com/dan323/http-client/releases/latest): `.msi` (Windows), `.dmg` (macOS) or `.deb` (Linux).
2. Run it. The installers are not signed, so Windows or macOS may warn you the first time.
3. Start **HTTP Client**.

## 2. Open or create a file

1. Press **Open…** and pick a `.http` file, or **New…** to create one. **Recent files** lists the last ten.
2. The text of the file appears in the editor. Edit it as usual and press **Save**; a dot after the file name means unsaved changes.

The app reopens the last file, its environment and the window position the next time you start it.

## 3. Choose an environment

1. Open the **Environment** picker. It lists `none` and every name in the nearest `http-client.env.json`.
2. Pick one. `none` uses only the variables written in the file.

## 4. Send a request

1. Select a request in the request list (requests are shown by their `# @name`, or as "Request 1", "Request 2"…).
2. Press **Send**, or **Ctrl+Enter** (**Cmd+Enter** on macOS). The file is saved first.
3. Read the **Response**: status, time and size, then **Headers** and **Body**. Switch the body between **Pretty** and **Raw**, and use **Copy** to put it on the clipboard.
4. If the file has `# @assert` lines, each result is shown as PASS or FAIL under the status line.
5. To stop a request that is taking too long, press **Cancel** (next to **Send**; it is enabled only while a request runs). The response area shows **Cancelled** instead of an error, and results that had already arrived stay.

Errors (an undefined variable, a connection failure, a parse problem) are shown in the response area instead of a response.

## 5. Requests that depend on each other

When a request uses {% raw %}`{{login.response.body.token}}`{% endraw %}, **Send** first runs the `login` request, then yours. Responses already received are reused, so the login is not sent again the next time; the viewer marks those as "Reused earlier response (not sent again)".

- **Reset chain** forgets the remembered responses, so the next **Send** runs the whole chain again.
- **Send all** runs every request in the file, always sending them again.

Only one run happens at a time. While it runs, editing, saving, opening and creating files are disabled.

## More

What the app remembers and where, and the exact behavior: [feature reference, desktop app](https://github.com/dan323/http-client/blob/master/docs/features.md#desktop-app).
