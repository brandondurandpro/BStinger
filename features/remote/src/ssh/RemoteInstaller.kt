package org.example.kotlineditor.remote

import org.example.kotlineditor.rpc.Protocol
import java.io.File
import java.util.Properties
import java.util.UUID

/** A server running on the remote host, reachable on its loopback through the SSH tunnel. */
data class RemoteServerInfo(val host: String, val port: Int, val token: String, val pid: Long)

/** What the remote machine is, and the home directory of the SSH user (where folder browsing starts). */
data class RemoteHost(val target: RemoteTarget, val home: String)

/**
 * Installs the server bundle on the remote host and keeps its per-user daemon alive.
 *
 * Everything lives under `~/.kotlin-editor/server`:
 * - `2026.1-linux-x86_64-<build>/` an unpacked bundle (a jlink runtime, the server jar, `start.sh`), its `BUILD`
 *   id and its `PROTOCOL`; one per build, side by side, so that IDEs of several builds share a daemon;
 * - `token` the secret, generated once and kept across restarts;
 * - `state.properties` what the running server wrote once it listened (port, pid, version);
 * - `server.log` its output.
 *
 * The token is created and the daemon started by the remote shell, so nothing secret travels on a command line.
 */
@Suppress("StringShouldBeRawString") // the SSH commands mix $HOME, quotes and Kotlin interpolation
class RemoteInstaller(private val ssh: SshClient, private val version: String = Protocol.APP_VERSION) {
    /** Operating system, CPU and SSH home directory of the remote host. */
    suspend fun detectHost(): RemoteHost {
        val unix =
            ssh.exec(
                "uname -s; uname -m; echo \"\$HOME\"",
                timeoutMs = 20_000,
            )
        // ssh itself failed (unknown host key, refused key, no route): the remote OS is not the question.
        if (unix.code == SSH_FAILURE) {
            throw RemoteException("Cannot connect: ${explainSshError(unix.error.ifBlank { unix.output })}")
        }
        if (unix.code == 0) {
            val lines = unix.output.lines().map { it.trim() }
            val os = lines.getOrNull(0)?.let(RemoteTarget::osOf)
            val arch = lines.getOrNull(1)?.let(RemoteTarget::archOf)
            if (os != null && arch != null) {
                val reported = lines.getOrNull(2)?.trim().orEmpty()
                val home = if (reported.startsWith("/")) reported else "/"
                return RemoteHost(RemoteTarget(os, arch), home)
            }
        }
        // Windows OpenSSH runs cmd.exe by default, where uname does not exist.
        val windows =
            ssh.execRaw(
                "powershell -NoProfile -NonInteractive -Command " +
                    "\"[System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString(); " +
                    "\$env:OS; \$env:USERPROFILE\"",
                timeoutMs = 20_000,
            )
        if (windows.code == 0) {
            val lines = windows.output.lines().map { it.trim() }
            val arch = lines.getOrNull(0)?.let(RemoteTarget::archOf)
            if (arch != null) {
                return RemoteHost(
                    RemoteTarget(RemoteTarget.WINDOWS, arch),
                    lines.getOrNull(2)?.ifBlank { "/" } ?: "/",
                )
            }
        }
        throw RemoteException(
            "Cannot determine the remote operating system. uname said: ${unix.error.ifBlank { unix.output }}",
        )
    }

    /**
     * Makes sure a daemon this IDE can use is running, installing this IDE's bundle when needed, and returns where
     * it listens. Also called when a connected daemon stops answering: it restarts a daemon that died (and which
     * then listens on a new port), and is a no-op while the daemon is alive.
     *
     * A bundle is identified by the hash of its archive, not by [Protocol.APP_VERSION]: a rebuilt server with the
     * same version number is still installed, in a directory of its own next to the others. A running daemon of
     * the same [Protocol.VERSION] is reused whatever its bundle, so an IDE update keeps its terminals; one of
     * another protocol is replaced, but while it has sessions only when [replace] is true (the user agreed):
     * otherwise this throws [RemoteServerInUseException].
     */
    @Suppress("ThrowsCount") // each failure has its own actionable message
    suspend fun ensureServer(
        target: RemoteTarget,
        onProgress: (String) -> Unit = {},
        replace: Boolean = false,
    ): RemoteServerInfo {
        if (target.isWindows) {
            throw RemoteException(
                "Remote Windows servers are not supported yet: the daemon scripts are POSIX only. " +
                    "Use a Linux or macOS host for now.",
            )
        }
        val bundle = RemoteBundles.find(target)
        val build = RemoteBundles.buildId(bundle)
        if (installedBuild(target, build) != build) {
            onProgress("Installing the server on ${target.id}")
            install(target, bundle, build)
        }
        onProgress("Starting the server")
        start(target, build, replace)
        val running =
            readState()
                ?: throw RemoteException(
                    "The remote server did not write its state file. " +
                        "See ~/.kotlin-editor/server/server.log on the host.",
                )
        if (running.protocol != Protocol.VERSION) {
            throw RemoteException(
                "The remote server runs bundle ${running.build.ifEmpty { "?" }} (protocol ${running.protocol}), " +
                    "this IDE installed $build (protocol ${Protocol.VERSION}). " +
                    "See ~/.kotlin-editor/server/server.log on the host.",
            )
        }
        return RemoteServerInfo("127.0.0.1", running.port, running.token, running.pid)
    }

    // One directory per build: a daemon keeps running from its own while another IDE installs a newer one.
    private fun directory(target: RemoteTarget, build: String) =
        "\$HOME/.kotlin-editor/server/$version-${target.id}-$build"

    private suspend fun installedBuild(target: RemoteTarget, build: String): String =
        ssh
            .exec("cat \"${directory(target, build)}/BUILD\" 2>/dev/null || true")
            .requireReachable()
            .output
            .trim()

    // Every install uses its own archive and staging directory: an abandoned install (cancelled, cut) or another
    // IDE installing at the same time cannot mix its files with this one.
    private suspend fun install(target: RemoteTarget, bundle: File, build: String) {
        val nonce = UUID.randomUUID().toString().take(NONCE_LENGTH)
        val archive = "\$HOME/.kotlin-editor/server/bundle-${target.id}-$nonce.tar.gz"
        // What abandoned installs left behind is removed an hour later.
        val upload =
            "R=\"\$HOME/.kotlin-editor/server\"; mkdir -p \"\$R\" && " +
                "find \"\$R\" -maxdepth 1 -mmin +60 \"(\" -name \"bundle-*.tar.gz\" -o -name \"*.tmp\" " +
                "-o -name \"*.old\" \")\" -exec rm -rf {} + 2>/dev/null; cat > \"$archive\""
        ssh.exec(upload, stdin = bundle, timeoutMs = 300_000).requireSuccess("Uploading the server bundle")
        val command =
            "D=\"${directory(target, build)}\"; A=\"$archive\"; T=\"\$D.$nonce.tmp\"; O=\"\$D.$nonce.old\"; " +
                "rm -rf \"\$T\"; mkdir -p \"\$T\" && tar -xzf \"\$A\" -C \"\$T\" && " +
                "echo $build > \"\$T/BUILD\" && echo ${Protocol.VERSION} > \"\$T/PROTOCOL\" && " +
                "chmod +x \"\$T/start.sh\" && " +
                "{ if [ -e \"\$D\" ]; then mv \"\$D\" \"\$O\"; fi; mv \"\$T\" \"\$D\"; }; " +
                "S=\$?; rm -rf \"\$T\" \"\$O\" \"\$A\"; exit \$S"
        ssh.exec(command, timeoutMs = 120_000).requireSuccess("Unpacking the server bundle")
    }

    private suspend fun start(target: RemoteTarget, build: String, replace: Boolean) {
        val option = if (replace) " --replace" else ""
        val result = ssh.exec("sh \"${directory(target, build)}/start.sh\"$option", timeoutMs = 120_000)
        if (result.code == IN_USE) throw RemoteServerInUseException.fromOutput(result.output)
        result.requireSuccess("Starting the remote server")
    }

    private suspend fun readState(): RemoteServerState? {
        val text =
            ssh
                .exec("cat \"\$HOME/.kotlin-editor/server/state.properties\" 2>/dev/null || true")
                .requireReachable()
                .output
        if (text.isBlank()) return null
        val properties = Properties().apply { load(text.byteInputStream()) }
        return runCatching {
            RemoteServerState(
                version = properties.getProperty("version").orEmpty(),
                protocol = properties.getProperty("protocol").toInt(),
                port = properties.getProperty("port").toInt(),
                token = properties.getProperty("token").orEmpty(),
                pid = properties.getProperty("pid").toLong(),
                build = properties.getProperty("build").orEmpty(),
            )
        }.getOrNull()
    }

    private data class RemoteServerState(
        val version: String,
        val protocol: Int,
        val port: Int,
        val token: String,
        val pid: Long,
        val build: String,
    )

    private companion object {
        const val NONCE_LENGTH = 8

        /** Exit code of `start.sh` when the daemon to replace still has sessions and the user was not asked. */
        const val IN_USE = 3
    }
}

/**
 * The host runs a daemon of another protocol that still has sessions (terminals, agent conversations, a client
 * waiting to come back): replacing it with this IDE's server ends them, so the user decides. [sessions] is null when
 * the daemon is too old to tell.
 */
class RemoteServerInUseException(val sessions: Int?, val version: String, val protocol: Int?) :
    RemoteException(describe(sessions, version, protocol)) {
    companion object {
        /** Reads the line `in-use sessions=<n> version=<v> protocol=<p>` printed by `start.sh`. */
        fun fromOutput(output: String): RemoteServerInUseException {
            val line = output.lines().firstOrNull { it.startsWith("in-use ") }.orEmpty()
            val fields =
                line
                    .removePrefix("in-use ")
                    .split(' ')
                    .associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val sessions = fields["sessions"]?.toIntOrNull()
            val version = fields["version"].orEmpty()
            val protocol = fields["protocol"]?.toIntOrNull()
            return RemoteServerInUseException(sessions, version, protocol)
        }

        private fun describe(sessions: Int?, version: String, protocol: Int?): String {
            val shownVersion = version.ifEmpty { "another version" }
            val shownProtocol = protocol?.toString() ?: "?"
            val running = "The server running on the host ($shownVersion, protocol $shownProtocol)"
            val users =
                when (sessions) {
                    null -> "may still have sessions"
                    1 -> "still has 1 session"
                    else -> "still has $sessions sessions"
                }
            return "$running $users, and this IDE needs protocol ${Protocol.VERSION}. Replacing it ends their " +
                "terminals and agent conversations, maybe another IDE's."
        }
    }
}

// A `cat … || true` cannot fail by itself: a failure is ssh's, and must not read as "nothing installed".
private fun SshResult.requireReachable(): SshResult = if (code == SSH_FAILURE) requireSuccess("SSH") else this
