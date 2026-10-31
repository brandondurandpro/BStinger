package org.example.kotlineditor.remote

import org.example.kotlineditor.rpc.Protocol
import java.io.File
import java.util.Properties

/** A server running on the remote host, reachable on its loopback through the SSH tunnel. */
data class RemoteServerInfo(val host: String, val port: Int, val token: String, val pid: Long)

/** What the remote machine is, and the home directory of the SSH user (where folder browsing starts). */
data class RemoteHost(val target: RemoteTarget, val home: String)

/**
 * Installs the server bundle on the remote host and keeps its per-user daemon alive.
 *
 * Everything lives under `~/.kotlin-editor/server`:
 * - `2026.1-linux-x86_64/` the unpacked bundle (a jlink runtime, the server jar, `start.sh`);
 * - `token` the secret, generated once and kept across restarts;
 * - `state.properties` what the running server wrote once it listened (port, pid, version);
 * - `server.log` its output.
 *
 * The token is created and the daemon started by the remote shell, so nothing secret travels on a command line.
 */
@Suppress("StringShouldBeRawString") // the SSH commands mix $HOME, quotes and Kotlin interpolation
class RemoteInstaller(private val ssh: SshClient, private val version: String = Protocol.APP_VERSION) {
    /** Operating system, CPU and SSH home directory of the remote host. */
    fun detectHost(): RemoteHost {
        val unix =
            ssh.exec(
                "printf '%s\\n' \"\$(uname -s)\" \"\$(uname -m)\" \"\$HOME\"",
                timeoutMs = 20_000,
            )
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
            ssh.exec(
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

    /** Makes sure the matching server daemon is running, installing or upgrading it when needed. */
    @Suppress("ThrowsCount") // each failure has its own actionable message
    fun ensureServer(target: RemoteTarget, onProgress: (String) -> Unit = {}): RemoteServerInfo {
        if (target.isWindows) {
            throw RemoteException(
                "Remote Windows servers are not supported yet: the daemon scripts are POSIX only. " +
                    "Use a Linux or macOS host for now.",
            )
        }
        val bundle = RemoteBundles.find(target)
        var state = readState()
        if (remoteVersion(target) != version) {
            if (state != null) stopDaemon(state)
            onProgress("Installing the server on ${target.id}")
            install(target, bundle)
            state = null
        } else if (state != null && state.version != version) {
            stopDaemon(state)
        }
        onProgress("Starting the server")
        start(target)
        val running =
            readState()
                ?: throw RemoteException(
                    "The remote server did not write its state file. " +
                        "See ~/.kotlin-editor/server/server.log on the host.",
                )
        if (running.version != version || running.protocol != Protocol.VERSION) {
            throw RemoteException(
                "The remote server runs version ${running.version} (protocol ${running.protocol}), " +
                    "this IDE needs $version (protocol ${Protocol.VERSION}).",
            )
        }
        return RemoteServerInfo("127.0.0.1", running.port, running.token, running.pid)
    }

    private fun remoteVersion(target: RemoteTarget): String =
        ssh
            .exec("H=\"\$HOME\"; cat \"\$H/.kotlin-editor/server/$version-${target.id}/VERSION\" 2>/dev/null || true")
            .output
            .trim()

    private fun install(target: RemoteTarget, bundle: File) {
        val archive = "\$H/.kotlin-editor/server/bundle-${target.id}.tar.gz"
        val uploaded =
            ssh.exec(
                "H=\"\$HOME\"; mkdir -p \"\$H/.kotlin-editor/server\"; cat > \"$archive\"",
                stdin = bundle,
                timeoutMs = 300_000,
            )
        uploaded.requireSuccess("Uploading the server bundle")
        val dir = "\$HOME/.kotlin-editor/server/$version-${target.id}"
        val command =
            "H=\"\$HOME\"; R=\"\$H/.kotlin-editor/server\"; D=\"$dir\"; T=\"\$R/$version-${target.id}.tmp\"; " +
                "rm -rf \"\$T\"; mkdir -p \"\$T\"; " +
                "tar -xzf \"\$R/bundle-${target.id}.tar.gz\" -C \"\$T\"; " +
                "rm -rf \"\$D.old\"; if [ -e \"\$D\" ]; then mv \"\$D\" \"\$D.old\"; fi; " +
                "mv \"\$T\" \"\$D\"; rm -rf \"\$D.old\" \"\$R/bundle-${target.id}.tar.gz\"; " +
                "chmod +x \"\$D/start.sh\""
        ssh.exec(command, timeoutMs = 120_000).requireSuccess("Unpacking the server bundle")
    }

    private fun start(target: RemoteTarget) {
        val script = "\$HOME/.kotlin-editor/server/$version-${target.id}/start.sh"
        ssh
            .exec("H=\"\$HOME\"; sh \"$script\"", timeoutMs = 120_000)
            .requireSuccess("Starting the remote server")
    }

    private fun stopDaemon(state: RemoteServerState) {
        if (state.pid <= 0) return
        ssh.exec(
            "H=\"\$HOME\"; kill ${state.pid} 2>/dev/null || true; " +
                "rm -f \"\$H/.kotlin-editor/server/state.properties\"",
            timeoutMs = 30_000,
        )
    }

    private fun readState(): RemoteServerState? {
        val text =
            ssh
                .exec("H=\"\$HOME\"; cat \"\$H/.kotlin-editor/server/state.properties\" 2>/dev/null || true")
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
            )
        }.getOrNull()
    }

    private data class RemoteServerState(
        val version: String,
        val protocol: Int,
        val port: Int,
        val token: String,
        val pid: Long,
    )
}
