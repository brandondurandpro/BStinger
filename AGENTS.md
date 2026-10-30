# Agent guidelines

Rules for anyone, human or AI agent, changing this repository.

## Every feature needs end-to-end tests

A feature, or a fix of user-visible behavior, is not done until an e2e test exercises it through the real UI.

- **New feature**: add e2e tests covering its main path and its edge cases (empty input, errors, cancelling).
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

## Before opening a pull request

- `./lint` (detekt + ktlint) passes.
- `./kotlin test` passes; `./kotlin test -m <module>` runs a single module while iterating.
- `main` only accepts squash or rebase merges, once the Lint, Linux and Windows CI checks are green.
