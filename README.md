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
- **Remote development**: *Remote Development* installs the server on a Linux or macOS host over SSH, starts its
  per-user daemon on the host's loopback and tunnels to it. The dialog is a pick view: remote folders already
  opened first, then the `~/.ssh/config` host catalog (alias, includes, `ssh -G` resolution) and tags like
  production/dev/test, all searchable; a "New host…" button covers manual entries. Connecting without a project
  opens the Open Project dialog on the host, in the SSH user's home (browse, go up or home, type a path or `~`,
  then open the directory). Key/agent authentication, automatic tunnel reconnection, strict host-key checking, no Java
  needed on the host (the bundle carries a jlink runtime).
  (the bundle carries a jlink runtime). See [docs/REMOTE.md](docs/REMOTE.md).
- **Project view**: lazily loaded, virtualized file tree (directories first), keyboard navigation (arrows, Enter),
  double click to open, right click for *New File / New Directory / Rename / Delete / Copy Path*, resizable and
  togglable (`Alt+1`), *Select in Project View* (`Alt+F1`).
- **Git panel** (`Alt+9`): changed files against `HEAD` below the project view, with IntelliJ-style status letters and
  colors; a click opens the diff of the file in a tab (green additions, red removals, old and new line numbers),
  switchable to a side-by-side view; its *Open File* button (`F4`) opens the file at its first change. The editor gutter marks added, modified and removed lines, *Annotate* shows the
  blame (author and date), and the panel commits, switches branches, pulls and pushes.
  Untracked files show as additions; the panel refreshes after a save or with its refresh button.
- **Tool windows**: *Project*, *Git* and *Terminal* can be shown/hidden (`Alt+1`, `Alt+9`, `Alt+F12`), resized with
  their splitters, and dragged by their header to the left, right or bottom dock. Their buttons stay fixed in the left
  strip, so a panel never moves its button.
- **Network devtools** (`Ctrl+Alt+Shift+D`, hidden otherwise): every message exchanged with the server, with its kind,
  method, payload and wire sizes, latency, the JSON-RPC message pretty-printed and its hex dump. The capture only runs
  while the panel is shown, keeps the last 2,000 messages (32 MB at most) and never keeps the token of the handshake.
- **Editor tabs**: open next to the active tab, most-recently-used activation on close, middle click to close,
  right click for *Close / Close Others / Close All to the Left or Right / Close All / Copy Path*, `Ctrl+F4`,
  `Alt+←/→`, `Ctrl+Tab`, per-tab scroll position, modified indicator.
- **Code editor**: virtualized rendering (only visible lines are composed, tested with 200k lines), tree-sitter
  syntax highlighting for 85 languages (each grammar downloaded the first time a file of its language opens, see
  [`tools/tree-sitter`](tools/tree-sitter/README.md); a line lexer colors the rest and fills in until then), JetBrains Mono, line numbers, caret line, selection, identifier usages
  and matching brackets highlighting, auto-closing brackets/quotes, smart Home, auto-indent, undo/redo, clipboard,
  IntelliJ shortcuts (`Ctrl+D`, `Ctrl+Y`, `Ctrl+/`, `Tab`/`Shift+Tab`, `Ctrl+Shift+Z`…).
- **Saving like IntelliJ**: `Ctrl+S`, and automatic save when a tab, the project or the window is closed, or when the
  window loses focus; external changes are reloaded when it regains focus (unsaved edits are never overwritten).
- **Navigation & search**: Go to File (`Ctrl+P`, `Ctrl+Shift+N` or double `Shift`, fuzzy matching), Recent Files (`Ctrl+E`),
  Find in Files (`Ctrl+Shift+F`), Find in file (`Ctrl+F`, `Enter`/`Shift+Enter`, `F3`), Go to Line (`Ctrl+G`).
- **Status bar & toolbar**: breadcrumbs, caret position, selection size, line separator, encoding, language, and a
  Git branch widget. It opens a searchable branch picker: pin one or several branches in the toolbar, read how many
  commits each is ahead (`↑`) or behind (`↓`) its remote-tracking branch, and click a branch to check it out. Tick
  several branches to pin, unpin or delete them at once; a shortcut cleans up the branches whose remote is gone or
  missing. Every deletion first lists the branches it will delete, and each one can be spared.
  The menu of a pinned branch pulls it (fast-forward only) or pushes it without checking it out, checks it out, or
  opens the Git tool window; the toolbar shows the result.
- **Branches tool window** (clock icon of the left strip, docked at the bottom): every local and remote branch, the
  history of the selected one (refs, subject, author, date, hash), loaded page by page as it scrolls, and the message
  and changed files of the selected commit (a click on a file opens the diff editor on what that commit changed in
  it). A pinned branch's menu opens it on that branch (Show History). Its search
  field finds the commits of the whole branch by message, author or start of the hash; splitters resize its panes,
  and the Local and Remote sections fold, their headers staying pinned while the branch list scrolls.

### Architecture: client and server

All the logic on files runs in a **server**; the IDE is a client that only displays.

- `protocol` – model shared by both sides, JSON-RPC codecs, and the network client (`RpcClient`).
- `compression` – body codec: zstd level 3, with a 64 KiB dictionary for source code. The session protocol is
  unchanged; only the message body is compressed.
- `server` – `EditorServer` and the local services: file system, project search (Find in Files and symbols run
  next to the files, only results travel), Git, shells, sessions and recent projects.
- `serverApp` – standalone server for remote use:
  `./kotlin run -m serverApp -- --host 0.0.0.0 --port 7777 --token <secret>`. It is also the daemon that remote
  development installs and starts (`--token-file`/`--state-file`, `--version`).
- `shared` – Compose UI and UI state; `desktopApp` – the IDE. By default it embeds a server on loopback; use
  `--server host:port --token <secret>` (or `KOTLIN_EDITOR_SERVER` / `KOTLIN_EDITOR_TOKEN`) for a remote one.
- **Remote development** – `shared/src@jvm/remote` (SSH client, installer, tunnel supervisor) and
  `shared/src/ui/RemoteDialog.kt`. Server bundles are built with `tools/remote-bundle.sh` and looked up in `remote/`
  next to the application (see [docs/REMOTE.md](docs/REMOTE.md)).

**Protocol.** JSON-RPC 2.0 over TCP (`TCP_NODELAY`), framed like LSP (`Content-Length` header, then the JSON body):
named params, methods such as `fs/read`, `git/status` or `terminal/start`, standard error codes, answers in any order,
server-pushed notifications for terminal screens (lines sent as style runs), bodies compressed with zstd-3 when it pays
(`Content-Encoding: zstd`, a code dictionary on file contents). See `protocol/src@jvm/rpc/Protocol.kt` for the mapping.
About 70 µs per round trip on loopback, a 200,000-line file in about 100 ms. TCP retransmits lost packets; the session
layer survives broken links:

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
- GitHub Actions (`.github/workflows/ci.yml`) runs `./lint` and the whole suite on Ubuntu 20.04, 22.04 and 24.04
  under X11 (Xvfb) and Wayland (headless Weston, with XWayland for AWT), plus Windows. Each Linux job also launches
  the desktop app and checks that it stays open, which proves the session really works.
- `./windows-vm` – the Windows job on your machine, without waiting for GitHub: `./windows-vm start` boots a local
  Windows Server 2025 VM (the image of `windows-latest`, evaluation edition) in Docker with KVM, through
  [dockur/windows](https://github.com/dockur/windows); the first start downloads and installs Windows (20 to 60
  minutes), the next ones take a minute. `./windows-vm test [args]` then runs `kotlin.bat test [args]` on the current
  working tree, uncommitted files included, checked out with CRLF line endings as on the CI, and exits with the
  tests' result. The desktop is on http://127.0.0.1:8006; `./windows-vm stop` keeps the disk, `destroy` deletes it.
- `./linux-vm` – the Linux jobs on your machine: `./linux-vm test [args]` runs `./kotlin build` then `./kotlin test
  [args]` on the current working tree, uncommitted files included, in Docker containers of Ubuntu 20.04, 22.04 and
  24.04 under X11 and Wayland, with the CI's packages and display sessions, and prints a summary of the six runs;
  without args it also checks that the desktop app starts. `--ubuntu 22.04` and `--session wayland` narrow the
  matrix, `./linux-vm shell` opens a shell in a container with the session started, `./linux-vm clean` deletes the
  images and caches.
- `./ci` – the whole CI on your machine: `./lint`, then `./linux-vm test`, then `./windows-vm test` (starting the
  VM if needed), with a summary at the end; `./ci --help` lists the options to skip a step or narrow the matrix.

The bundled JetBrains Mono font is licensed under the SIL Open Font License 1.1.

---

Learn more
about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html), [Kotlin Toolchain](https://kotlin-toolchain.org/latest/), [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/), [Kotlin/Wasm](https://kotl.in/wasm/), [Ktor](https://ktor.io/).
