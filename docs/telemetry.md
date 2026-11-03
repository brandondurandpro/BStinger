# Telemetry

Kotlin Editor can send anonymous performance measures, so that we can see which operations are slow, on which
versions and platforms. It is **off until you say yes**, and it never sends your code or anything that names it.

## Your choice

- The welcome screen asks once, without blocking anything: **Share** or **No, thanks**. *What is sent* lists the
  data below. Until you answer, nothing is measured.
- The project menu (the project name at the top left) has **Share Performance Measures** to change your mind. It
  takes effect at once: switched off, what was waiting to be sent is dropped.
- `KOTLIN_EDITOR_TELEMETRY=off` (also `false`, `0`, `no`) turns it off whatever you chose, on the IDE and on the
  server; the question is then not asked. Use it on CI machines or when your company does not allow it.
- The choice is kept in the IDE preferences: `telemetry.enabled` (absent means no) and `telemetry.instanceId`.
- A remote server measures only when the IDE connected to it passes your consent on (`telemetry/config`, sent on
  each connection and on each change). A server that restarted measures nothing until the IDE reconnects.

## Where it goes

The measures go over OTLP/HTTP to `http://localhost:4318`, or to `OTEL_EXPORTER_OTLP_ENDPOINT` when it is set:
traces (to Tempo) and the `kotlin_editor_operation_duration` histogram (to Mimir). `tools/telemetry` runs such a
collector with Grafana and a dashboard on your machine (see its README).

Sending never slows the IDE down: measures wait in a queue of 2,048 that drops the newest ones when full, are sent
in the background every 5 seconds (the histogram every 30), each request gives up after 2 seconds, and closing the
IDE waits at most 2 seconds for the last ones. A collector out of reach just loses them.

## What is sent

Each measure is one **operation**, its **duration**, its **outcome** and a few **attributes**, from this closed
list. The code checks each value against it; a value that does not fit becomes `other`.

| Operation | What it measures, until the frame that shows the result |
|---|---|
| `app.startup` | From the start of the JVM to the first frame |
| `project.open` | A project opened, until it shows |
| `editor.open_file`, `editor.save` | A file opened or saved |
| `editor.completion`, `editor.find`, `editor.navigation` | Completion items, matches in the file, a go to declaration |
| `editor.diff`, `editor.blame`, `git.commit_diff` | A diff, the blame annotations, the diff of a commit |
| `tree.expand`, `tree.goto_file`, `tree.index`, `tree.file_operation` | A folder, Go to File, the file list, a create/rename/delete |
| `search.find_in_files` | Find in Files, until its results show (they come in one answer) |
| `git.status`, `git.branch`, `git.history` | The status, the branch, a page of history |
| `terminal.start`, `terminal.echo` | A shell's first screen; a keystroke until its echo (one at a time) |
| `lsp.start` | A language server started, until it answered `initialize` |
| `remote.ssh_connect`, `remote.deploy` | Remote Development: the whole connection, the server installation |
| `connection.reconnect` | The link with the server lost, until it is back |
| `rpc.client`, `rpc.server` | Each request, on both sides; the server's is a child of the client's |

| Field | Values |
|---|---|
| outcome | `ok`, `cancelled`, or `error` with the **class** of the exception (`IOException`), never its message |
| `method` | The protocol method of a request (`fs/read`, `lsp/hover`) |
| `retried` | `true` when the request was sent again after a reconnection |
| `language` | The language of the file (`php`, `kotlin`) |
| `size` | A bucket: `<1k`, `<10k`, `<100k`, `<1M`, `<10M`, `>=10M` (lines, files or results) |
| `location` | `local` or `remote` |
| `result` | `found`, `empty` or `partial` |
| resource | `service.name` (`kotlin-editor-desktop` or `kotlin-editor-server`), `service.version`, `os.type`, and `service.instance.id`: a random id made when you said yes |

**Never sent**: a path, a file or folder name, a file's content, a search query, a branch or commit, a host name, a
user name, an error message, or anything else that is not in the tables above. The e2e tests
(`shared/test/e2e/TelemetryE2eTest.kt`) check it on what the IDE really measures.

## For developers

- `telemetry`: the catalog (`Operation`, `Attribute`, `Outcome`), `Telemetry` and its hooks. Disabled, it allocates
  nothing. `measureToFrame` (in `core`) ends a span at the frame that shows the result.
- `telemetry-otel`: `OtelHook`, the OpenTelemetry SDK configured by hand (no autoconfigure).
- A request carries the W3C `traceparent` of its span in the optional `trace` field of the JSON-RPC message; a peer
  that does not know it ignores it.
- Tests use `TelemetryCapture` (`e2e`), which keeps the spans of both sides in memory, and `FakeCollector`
  (`testkit`), an OTLP collector that can stop answering.
