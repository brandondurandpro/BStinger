# Agent guidelines

Rules for anyone, human or AI agent, changing this repository.

## Work in a worktree and a branch for each task

Every new task, feature or fix starts on its own branch, in its own git worktree, created from `main`.

- Do not start new work in the main checkout or on a branch that belongs to another task.
- Name the branch after the change (`feat/...`, `fix/...`, `perf/...`, `chore/...`).
- Remove the worktree once its branch is merged.

## Run the app and the tests

Use `./kotlin` (`kotlin.bat` on Windows); it downloads the toolchain on first use.

- Desktop app: `./kotlin run -m desktopApp` (embeds a server on loopback).
- All tests: `./kotlin test`; one module: `./kotlin test -m shared`.
- One test class: `./kotlin test -m shared --include-classes='*EditorTabsE2eTest'`; one test: `--include-test=<fqn>.method`.
- Lint: `./lint`.

## Every feature needs end-to-end tests

A feature, or a fix of user-visible behavior, is not done until an e2e test exercises it through the real UI.

- **New feature**: add e2e tests covering its main path and its edge cases (empty input, errors, cancelling),
  e2e performance tests and, when it talks to the server, e2e chaos tests (see below).
- **Bug fix**: first write an e2e test that reproduces the bug and fails, then fix it.
- **Changed behavior**: update the e2e tests that describe it; never delete or weaken a test just to make it pass.
- Unit tests (`shared/test@jvm/unit`, `server/test`) are welcome for logic, protocol and server code, but they do
  not replace the e2e test of a feature.
- A pull request that adds or changes a feature without e2e tests must say why (for example: no UI, server-only
  change covered by `server/test`).

### Writing e2e tests

- They live in `shared/test@jvm/e2e`, one `*E2eTest` class per area, with the Compose UI test API.
- Start the app with `launchApp` or `launchWithProject` from `E2eSupport.kt`: it runs a real `EditorServer` and the
  UI talks to it over the network, so every test also goes through the protocol. Use `SampleProject` for files.
- Drive the app like a user: clicks, keys and shortcuts on nodes found by the tags of `ui/Tags.kt` (add a tag when
  a new element needs one). Wait with `awaitTag`, `awaitNoTag` and `awaitCondition`, never with `sleep`.
- Use `androidx.compose.ui.test.v2.runComposeUiTest`, not the v1 API.
- Tests must pass on every CI platform: Linux (X11 and Wayland, where `/bin/sh` may be dash) and Windows (`cmd.exe`
  through ConPTY). Do not rely on bash, on the shell prompt, on a POSIX command or on the system clipboard (the e2e
  clipboard is kept in memory).

### Writing e2e performance tests

Every feature also gets e2e performance tests, which drive it through the real UI on a large input (a directory of
thousands of files, a file of thousands of lines, a long search result…).

- They live next to the e2e tests, in a `*PerformanceE2eTest` class per area, and follow the same rules.
- Wrap each measured step in `measure(label, budgetMillis, budgetMegabytes) { … }` from `Cost.kt` (e2e): it prints
  a `PERF` line (time and memory allocated) and fails over budget. Render the frames being measured with `renderFrame()`
  (`waitForIdle()` draws the frame; `captureToImage()` draws nothing more and leaks native memory until a GC:
  thousands of them got the CI runners killed).
- For scrolling or any per-frame work, measure the net cost like `EditorScrollBenchmark`: subtract the cost of
  rendering unchanged frames (`costOf`, then `report`), drive the scroll state directly rather than through a
  semantics action, and measure a scroll in progress (wheel, drag) as well as jumps.
- Pick budgets several times what your machine measures, so that a slow CI runner passes but an algorithmic
  regression (a linear scan per frame, a quadratic rebuild) fails. Also assert what keeps it fast when it can be
  checked, such as the number of composed rows of a virtualized list.
- Give the measurements before and after in the pull request of a performance change.

### Writing e2e chaos tests

The IDE is a client of a server that may be remote: every feature that talks to the server also gets e2e chaos
tests, which check what the user sees when the network or the server fails.

- They live next to the e2e tests, in a `*ChaosE2eTest` class per area (`ServerConnectionE2eTest` for the
  connection itself), and follow the same rules.
- Launch with `launchWithProject(project, chaos = true)`: the UI then reaches the server through a `ChaosProxy`
  (`testkit` module), `app.network`, and notices a dead link within a second. `app.process` stops, starts and
  restarts the server on the same port and data.
- Only test failures that really happen, and that the feature can meet:
  - the network goes down for a while, then comes back (`goDown`, `comeBack`): Wi-Fi off, VPN reconnecting;
  - the link goes silent without closing (`blackHole`): laptop sleep, NAT or Wi-Fi dropped silently;
  - the connection breaks after the server ran a request but before its answer arrived (`dropFromServer`, then
    `cutAll`): the retry must not run a mutation twice;
  - a slow link (`latencyMs`): the UI must stay responsive and late answers must not override newer ones;
  - the server restarts (`app.process.restart()`), losing its sessions (shells), or comes back with another token;
  - another program changes the disk under the IDE (a file deleted while listed or open).
- Do not invent failures the protocol rules out (bytes lost or reordered on a live TCP connection, corrupted
  frames): they are covered by the server's tests, not by the UI.
- Assert what the user sees and what ends up on disk: the status bar (`awaitReconnecting`, `awaitReconnected`), the
  data (written once, nothing lost), and that nothing stays stuck loading or pretends to work.

## Before opening a pull request

- `./lint` (detekt + ktlint) passes.
- `./kotlin test` passes; `./kotlin test -m <module>` runs a single module while iterating.
- `main` only accepts squash or rebase merges, once the Lint, Linux and Windows CI checks are green.
