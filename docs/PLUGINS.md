# Plugins

A plugin adds a whole feature to the IDE: a tool window, its shortcuts, server-side code that runs next to the files,
and its tests. The bundled features (Run, Git, Terminal) use the same registries as an external plugin.

## Layout of a plugin

```
my-plugin/
  plugin.yaml     id, version, apiVersion, client, server, dependencies, permissions
  client.jar      Compose UI, loaded by the desktop app (class named by `client`, a ClientPlugin)
  server.jar      optional, loaded by the server, local or remote (class named by `server`, a ServerPlugin)
  shared.jar      optional, loaded on both sides
```

`plugin.yaml` is a flat subset of YAML (`key: value`, and `key:` followed by `- item` lines):

```yaml
id: todo
name: Todo explorer
version: 1.0
apiVersion: 1
client: todo.TodoClient
server: todo.TodoServer
permissions:
  - fs-write
```

A plugin installs in `~/.kotlin-editor/plugins/<id>/`, from the Plugins settings (Settings, Plugins: a `.plugin` file
is a zip of the files above), with no restart.

## The two halves

- `ClientPlugin.register(ClientContributions)`: `toolWindow(...)`, `shortcut(...)`, `onUnload(...)`.
- `ServerPlugin.register(ServerContributions)`: `handle(PluginMethod, handler)`, `onUnload(...)`. Methods are named
  `plugin/<id>/<name>` and say whether they are `mutating`; replay after a reconnection, deduplication, compression and
  streams come from the session layer.
- The client calls its server half through `ProjectHost.pluginServer`.

At each connection the client compares the server halves it has on disk with those installed on the server (SHA-256) and
sends the missing or different ones with `plugin/install`. A plugin whose server half the server cannot take is turned
off on both sides, with the reason shown in the Plugins settings.

## Isolation and unloading

Each half has a class loader of its own whose parent only exposes the plugin API, the UI kit, Compose, coroutines and
serialization (client), or the protocol and the server plugin API (server). Everything a plugin registers is removed
when its registration is closed. After unloading, a weak reference to the class loader must be cleared by the garbage
collector; if something still holds it (a thread, a listener, a native library), the IDE reports that a restart is
required rather than running two versions side by side.

A plugin that did not finish starting leaves a `.loading` marker in its folder; the next start keeps it off until the
user enables it in the settings. This is not a sandbox: a plugin has the rights of the user.

## API stability

`plugin-api/api/plugin-api.api` is the checked-in dump of the public API; `ApiDumpTest` fails when it changes. Review
the change, then run the test with `UPDATE_API_DUMP=1`. `apiVersion` in the manifest must equal the IDE's
(`API_VERSION` in `plugin-core`).

## Testing a plugin

`launchWithProject(project, plugins = listOf(IdePlugin(...)))` loads contributions given as objects;
`launchWithProject(project, pluginsDirectory = folder)` loads installed plugins and keeps their server halves in sync,
so a plugin gets the e2e, performance, chaos and side-effect tests required by `AGENTS.md` like a built-in feature.
`PluginFixture` (in `testkit`) builds plugin jars from Java source at test time.

## Not done yet

Signing and compatibility ranges (for third parties), a declarative tier for untrusted contributions, and the physical
move of the bundled features into `plugins/<id>/{shared,client,server}`: Run, Git and Terminal contribute through the
registries, but their server code is still in `server/`.
