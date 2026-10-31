# Remote development

The IDE can run its server on another machine and connect to it over SSH. All file system, search, Git and shell
work happens next to the sources; only the editor state and the results travel.

## Using it

1. Open **Remote Development** on the welcome screen.
2. Enter the host, optionally the user, port and identity file. Leave the project directory empty to browse it.
3. **Connect**.

The first connection installs the matching server under `~/.kotlin-editor/server` on the host, starts a per-user
daemon and opens an SSH tunnel. Later connections reuse the daemon; the SSH link is supervised and restarted when
the network comes back.

If no project directory was given, a folder browser opens in the **SSH user's home**. Click a folder to list its
subfolders, use **Up** and **Home**, type an absolute path (or `~`) in the path field, and **Open** the folder
shown. The path is checked as you type, with a 250 ms debounce, so pausing on a valid folder updates the list
without pressing Enter; Enter checks immediately. The project view's *Open…* menu starts the same browser in the
current project. Nothing is written on the host while browsing.

Authentication is key or `ssh-agent` only: the client runs OpenSSH in batch mode, so it never asks for a password.
`~/.ssh/config`, `ProxyJump`, known hosts and hardware keys therefore work as with `ssh` itself.

## Prerequisites

- The client machine needs the OpenSSH client (`ssh`) on its `PATH`.
- The host key must already be in `~/.ssh/known_hosts`: connect once with `ssh user@host`, or run
  `ssh-keyscan host >> ~/.ssh/known_hosts` after checking the fingerprint. Host keys are checked strictly.
- The server host must run Linux or macOS, with a POSIX `sh`, `tar`, `sed`, `mkdir`, `chmod`, `nohup`, `kill` and
  either `openssl` or `od`. This is the default on every supported distribution.
- The host needs **no Java**: the bundle carries a jlink runtime. The first upload is about 65 MB; later connections
  only transfer the protocol.

## Building the server bundle

A bundle is a `.tar.gz` whose root holds `VERSION`, `serverApp.jar`, `runtime/` (a jlink image) and `start.sh`.
Build one for the machine running the command:

```sh
./tools/remote-bundle.sh
```

Cross-build for another target. `--jdk` points at a JDK whose `jmods/` are for that platform; the `jlink` tool
itself runs on the build machine (override it with `--jlink` if the versions differ). If `jlink` refuses a foreign
target on your machine, run the script on that platform:

```sh
./tools/remote-bundle.sh --target linux-aarch64 --jdk /path/to/aarch64/jdk
./tools/remote-bundle.sh --target macos-aarch64 --jdk /path/to/macos/jdk
```

Put the results next to the application, in `remote/` (`server-linux-x86_64.tar.gz`, …), or point a development run
at one with `-Dkotlin.editor.remote.bundle=/path/to/server-linux-x86_64.tar.gz`. The IDE also looks in
`build/remote/` and `~/.kotlin-editor/bundles/`.

## Security

- The server listens on `127.0.0.1` only; the SSH tunnel is the only way in.
- The token is generated on the host, kept in `~/.kotlin-editor/server/token` (mode 600) and passed to the server
  through a file, never on a command line. The state file with the port and token is `600` too, and the directory
  is `700`.
- Host keys are checked strictly (`StrictHostKeyChecking=yes`). A changed host key stops the tunnel and the status
  bar says why; it is never silently accepted.
- No password is stored or forwarded.

## How it works

```
IDE ── RpcClient ── 127.0.0.1:<local port> ── ssh -N -L ── 127.0.0.1:<server port> ── daemon
```

- `RemoteInstaller` detects the host (`uname`), uploads the bundle for that target if its `VERSION` differs, unpacks
  it, starts `start.sh` and reads the daemon's `state.properties`.
- `start.sh` is idempotent: it reuses a live daemon, otherwise it generates the token once and starts the server
  detached (`setsid`/`nohup`). The daemon keeps running across IDE restarts.
- `RemoteTunnel` runs one `ssh -N -L` and restarts it with backoff when it dies. The daemon never stopped, so the
  protocol client reconnects and resumes its sessions and terminal streams. The remote daemon keeps a disconnected
  session (its shells, its pending answers) for 30 minutes, so a sleeping laptop or a long VPN outage does not kill
  its terminals.
- SSH compression is disabled: the protocol already compresses bodies with zstd. One SSH connection carries the
  tunnel, and the forwarded port uses `TCP_NODELAY` on both ends.

## Limitations

- Remote **Windows** servers are not supported yet. The daemon scripts are POSIX; the dialog reports this clearly.
  macOS and Linux targets share the same POSIX path. Windows clients can connect to Linux/macOS hosts.
- One saved remote connection replaces the previous one; there is no list of hosts yet.
- The daemon is started per user, not through systemd/launchd. A reboot requires reconnecting from the IDE.

## Tests

`RemoteDevelopmentE2eTest`, `RemoteDevelopmentChaosE2eTest`, `RemoteFolderPickerE2eTest` and
`RemoteDevelopmentPerformanceE2eTest` run a real Apache MINA SSH server on the test machine, point its `HOME` at a
temporary directory and connect the real OpenSSH client to it. They cover install/daemon/tunnel, editing and saving,
cancellation and validation, disconnecting, tunnel restarts, a changed host key, the folder browser (SSH home,
click-through navigation, live path check with debounce, path errors, opening the chosen folder), a listing that
completes after a reconnection,
1,000 folders, and a 5,000-line file on a 200-file project. `RemoteTransportTest` covers the token surviving a
daemon restart and strict host-key checking.
