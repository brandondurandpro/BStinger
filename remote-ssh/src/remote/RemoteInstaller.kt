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
 * - `2026.1-linux-x86_64/` the unpacked bundle (a jlink runtime, the server jar, `start.sh`) and its `BUILD` id;
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
     * Makes sure the daemon of this IDE's bundle is running, installing or upgrading it when needed, and returns
     * where it listens. Also called when a connected daemon stops answering: it restarts a daemon that died (and
     * which then listens on a new port), and is a no-op while the daemon is alive.
     *
     * A bundle is identified by the hash of its archive, not by [Protocol.APP_VERSION]: a rebuilt server with the
     * same version number is still installed, and a daemon of another bundle is replaced by `start.sh`.
     */
    @Suppress("ThrowsCount") // each failure has its own actionable message
    suspend fun ensureServer(target: RemoteTarget, onProgress: (String) -> Unit = {}): RemoteServerInfo {
        if (target.isWindows) {
            throw RemoteException(
                "Remote Windows servers are not supported yet: the daemon scripts are POSIX only. " +
                    "Use a Linux or macOS host for now.",
            )
        }
        val bundle = RemoteBundles.find(target)
        val build = RemoteBundles.buildId(bundle)
        if (installedBuild(target) != build) {
            onProgress("Installing the server on ${target.id}")
            install(target, bundle, build)
        }
        onProgress("Starting the server")
        start(target)
        val running =
            readState()
                ?: throw RemoteException(
                    "The remote server did not write its state file. " +
                        "See ~/.kotlin-editor/server/server.log on the host.",
                )
        if (running.build != build || running.protocol != Protocol.VERSION) {
            throw RemoteException(
                "The remote server runs bundle ${running.build.ifEmpty { "?" }} (protocol ${running.protocol}), " +
                    "this IDE installed $build (protocol ${Protocol.VERSION}). " +
                    "See ~/.kotlin-editor/server/server.log on the host.",
            )
        }
        return RemoteServerInfo("127.0.0.1", running.port, running.token, running.pid)
    }

    private fun directory(target: RemoteTarget) = "\$HOME/.kotlin-editor/server/$version-${target.id}"

    private suspend fun installedBuild(target: RemoteTarget): String =
        ssh
            .exec("cat \"${directory(target)}/BUILD\" 2>/dev/null || true")
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
            "D=\"${directory(target)}\"; A=\"$archive\"; T=\"\$D.$nonce.tmp\"; O=\"\$D.$nonce.old\"; " +
                "rm -rf \"\$T\"; mkdir -p \"\$T\" && tar -xzf \"\$A\" -C \"\$T\" && " +
                "echo $build > \"\$T/BUILD\" && chmod +x \"\$T/start.sh\" && " +
                "{ if [ -e \"\$D\" ]; then mv \"\$D\" \"\$O\"; fi; mv \"\$T\" \"\$D\"; }; " +
                "S=\$?; rm -rf \"\$T\" \"\$O\" \"\$A\"; exit \$S"
        ssh.exec(command, timeoutMs = 120_000).requireSuccess("Unpacking the server bundle")
    }

    private suspend fun start(target: RemoteTarget) {
        ssh
            .exec("sh \"${directory(target)}/start.sh\"", timeoutMs = 120_000)
            .requireSuccess("Starting the remote server")
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
    }
}

// A `cat … || true` cannot fail by itself: a failure is ssh's, and must not read as "nothing installed".
private fun SshResult.requireReachable(): SshResult = if (code == SSH_FAILURE) requireSuccess("SSH") else this
