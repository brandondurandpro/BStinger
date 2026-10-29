# Kotlin Multiplatform project

This project is targeting Desktop (JVM), built with
the [Kotlin Toolchain](https://kotlin-toolchain.org/latest/).

- [/desktopApp](./desktopApp) contains the desktop (JVM) application.
- [/shared](./shared) holds the code shared across your applications — Compose UI,
  business logic, and platform-specific implementations. [src](./shared/src) is for common code; the
  sibling `src@<platform>` folders (for example `src@android`) hold code compiled only for the platform named in the
  folder.

The `kotlin` (macOS/Linux) and `kotlin.bat` (Windows) scripts in the project root are self-bootstrapping wrappers for
the Kotlin Toolchain: they download the pinned toolchain version on first use, so no separate installation is required.
Build the whole project with `./kotlin build`.

### Running

Run each module from the command line:

- Desktop app: `./kotlin run -m desktopApp`

### Testing

Run the project's tests with `./kotlin test`, or `./kotlin test -m <module>` for a single module.

## Kotlin Editor

A lightweight IDE in the style of IntelliJ IDEA (New UI, dark theme), written with Compose Multiplatform for Desktop.

### Features

- **Welcome screen**: recent projects (searchable, removable), *Open* to pick a project directory.
- **Project view**: lazily loaded, virtualized file tree (directories first), keyboard navigation (arrows, Enter),
  double click to open, right click for *New File / New Directory / Rename / Delete / Copy Path*, resizable and
  togglable (`Alt+1`), *Select in Project View* (`Alt+F1`).
- **Git panel** (`Alt+9`): changed files against `HEAD` below the project view, with IntelliJ-style status letters and
  colors; a click opens the diff of the file in a tab (green additions, red removals, old and new line numbers).
  Untracked files show as additions; the panel refreshes after a save or with its refresh button.
- **Tool windows**: *Project*, *Git* and *Terminal* can be shown/hidden (`Alt+1`, `Alt+9`, `Alt+F12`), resized with
  their splitters, and dragged by their header to the left, right or bottom dock. Their buttons stay fixed in the left
  strip, so a panel never moves its button.
- **Editor tabs**: open next to the active tab, most-recently-used activation on close, middle click to close,
  `Ctrl+F4`, `Alt+←/→`, `Ctrl+Tab`, per-tab scroll position, modified indicator.
- **Code editor**: virtualized rendering (only visible lines are composed, tested with 200k lines), syntax
  highlighting (Kotlin, Java, C-like, scripts), JetBrains Mono, line numbers, caret line, selection, identifier usages
  and matching brackets highlighting, auto-closing brackets/quotes, smart Home, auto-indent, undo/redo, clipboard,
  IntelliJ shortcuts (`Ctrl+D`, `Ctrl+Y`, `Ctrl+/`, `Tab`/`Shift+Tab`, `Ctrl+Shift+Z`…).
- **Saving like IntelliJ**: `Ctrl+S`, and automatic save when a tab, the project or the window is closed, or when the
  window loses focus; external changes are reloaded when it regains focus (unsaved edits are never overwritten).
- **Navigation & search**: Go to File (`Ctrl+Shift+N` or double `Shift`, fuzzy matching), Recent Files (`Ctrl+E`),
  Find in Files (`Ctrl+Shift+F`), Find in file (`Ctrl+F`, `Enter`/`Shift+Enter`, `F3`), Go to Line (`Ctrl+G`).
- **Status bar & toolbar**: breadcrumbs, caret position, selection size, line separator, encoding, language,
  current Git branch.

### Architecture: client and server

All the logic on files runs in a **server**; the IDE is a client that only displays.

- `protocol` – model shared by both sides, binary codec, and the network client (`RpcClient`).
- `server` – `EditorServer` and the local services: file system, project search (Find in Files and symbols run
  next to the files, only results travel), Git, shells, sessions and recent projects.
- `serverApp` – standalone server for remote use:
  `./kotlin run -m serverApp -- --host 0.0.0.0 --port 7777 --token <secret>`.
- `shared` – Compose UI and UI state; `desktopApp` – the IDE. By default it embeds a server on loopback; use
  `--server host:port --token <secret>` (or `KOTLIN_EDITOR_SERVER` / `KOTLIN_EDITOR_TOKEN`) for a remote one.

**Protocol.** Custom binary frames over TCP (`TCP_NODELAY`): length-prefixed, multiplexed by request id, answers in any
order, server-pushed streams for shell output, large bodies deflated. About 70 µs per round trip on loopback, a
200,000-line file in about 100 ms. TCP retransmits lost packets; the session layer survives broken links:

- automatic reconnection with backoff, heartbeats (ping/pong) to detect silent dead links;
- unanswered requests are resent after reconnecting; mutating ones are deduplicated by request id on the server,
  so a write whose answer was lost is never applied twice;
- stream events are numbered: after a reconnection the client gets exactly what it missed, in order, once;
- token authentication, protocol version check, request cancellation and timeouts.

`server/test/ResilienceTest.kt` checks all of this through a fault-injecting proxy (cuts, lost answers, black holes,
server restart).

### Tests and code quality

- `./kotlin test` – unit tests, protocol and resilience tests, and end-to-end UI tests (Compose UI Test) that drive
  the real IDE against a real server on temporary projects.
- `./lint` – detekt with **every** rule enabled (`--all-rules`, unstable ones included) plus the ktlint rule set,
  failing on any finding; `./lint --fix` applies the automatic corrections. `detekt.yml` only adapts the few rules
  that contradict a convention of our tools, each with its reason.

The bundled JetBrains Mono font is licensed under the SIL Open Font License 1.1.

---

Learn more
about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html), [Kotlin Toolchain](https://kotlin-toolchain.org/latest/), [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/), [Kotlin/Wasm](https://kotl.in/wasm/), [Ktor](https://ktor.io/).
