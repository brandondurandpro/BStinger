# Native server

The server of the IDE can be compiled to a native binary with GraalVM (`native-image`): no JVM to start, and less
memory. Measured with a 6,000-file PHP project open, 80 s after start, on Linux x64:

| Server | Resident | Anonymous | Peak |
| --- | --- | --- | --- |
| Child JVM (default) | 143 MB | 109 MB | 233 MB |
| Native binary | 96 MB | 61 MB | 96 MB |

It is opt-in: without it the IDE starts a child JVM, as before.

## Build and use

```
./native-server build     # downloads GraalVM once (checked against its SHA-256), about one minute of compilation
./native-server test      # builds, then runs NativeServerTest against the binary
KOTLIN_EDITOR_SERVER_BINARY="$(./native-server path)" ./kotlin run -m desktopApp
```

The IDE starts the file named by `KOTLIN_EDITOR_SERVER_BINARY` (or the `kotlin.editor.server.binary` system
property) instead of `java`; it is supervised and restarted like the child JVM. The binary takes the options of
`ServerMain` and can be run by hand.

## Limits

- **Server plugins**: a native binary cannot load a jar. As soon as a plugin is installed in
  `~/.kotlin-editor/server-plugins`, the IDE starts the child JVM instead.
- **Linux x64 only** is built and tested. `native-image` does not cross-compile: another system needs its own
  GraalVM (`GRAALVM_HOME`) and its own run of the tests, and the metadata below only lists the native libraries of
  Linux x64.
- A binary that cannot start falls back to the server in the IDE's JVM, with a warning.
- The CI does not build it yet.
- No profile-guided optimization: `--pgo` only exists in Oracle GraalVM, not in the Community Edition used here.

## Memory given back

GraalVM's serial collector only runs when an allocation finds the young generation full: an idle server kept
what a burst left in its heap (89 MB after reading a large file sixty times, for 30 MB at rest). `MemoryReturn`
asks for a collection every 30 s when the heap has grown, then trims the C heap: 50 MB half a minute later.
A smaller young generation (`MaxNewSize`) was tried and dropped: it more than doubled the peak.

## Metadata

GraalVM only compiles what it can see: classes reached by reflection, resources and C functions called through
the Foreign Function & Memory API (the pseudo-terminal, `malloc_trim`) are declared in
`serverApp/resources/META-INF/native-image/org.example.kotlineditor/server/reachability-metadata.json`.

It was recorded by running the tests of the server-side modules on GraalVM with
`-agentlib:native-image-agent=config-merge-dir=<dir>` in their `freeJvmArgs`, then removing the entries of the
test libraries. After adding a library, a `ServiceLoader` or a native call to the server, record it again or add
the entry by hand: a missing one fails at run time with the entry to add in its message, and `./native-server test`
is there to catch it.
