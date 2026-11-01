package org.example.kotlineditor.remote

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Error of an SSH command, with what ssh wrote on its error stream (the useful part for the user). */
class SshException(message: String, val output: String = "") :
    IOException(
        if (output.isBlank()) message else "$message: $output",
    )

/** Result of a remote command. */
data class SshResult(val code: Int, val output: String, val error: String)

/** Fails with the command's error stream when it did not succeed. */
fun SshResult.requireSuccess(what: String): SshResult {
    if (code != 0) throw SshException("$what failed (exit code $code)", error.ifBlank { output })
    return this
}

/**
 * Runs the OpenSSH client, so remote development inherits the user's `~/.ssh/config`, agent, ProxyJump, known
 * hosts and hardware keys instead of reimplementing them. Only key and agent authentication is used: every
 * command runs in batch mode, without a terminal to prompt on.
 */
class SshClient(private val target: SshTarget) {
    private val executable: String = System.getProperty("kotlin.editor.ssh", "ssh")
    private val knownHosts: String? = System.getProperty("kotlin.editor.ssh.knownHosts")
    private val configFile: String? = System.getProperty("kotlin.editor.ssh.config")

    /** Runs [command] on the remote shell and returns its output; [stdin] is its standard input. */
    fun exec(command: String, stdin: File? = null, timeoutMs: Long = 30_000): SshResult =
        run(executable, listOf(executable) + arguments() + target.destination + " " + command, stdin, timeoutMs)

    /** Starts `ssh -N -L`, which forwards a local port to the server's loopback port. */
    fun forward(localPort: Int, remotePort: Int): ForwardProcess {
        val args =
            mutableListOf(executable)
                .apply {
                    addAll(arguments())
                    add("-o")
                    add("ExitOnForwardFailure=yes")
                    add("-N")
                    add("-L")
                    add("127.0.0.1:$localPort:127.0.0.1:$remotePort")
                    add(target.destination)
                }
        val process =
            ProcessBuilder(args)
                .redirectErrorStream(false)
                .start()
        return ForwardProcess(process)
    }

    private fun arguments(): List<String> =
        mutableListOf<String>()
            .apply {
                configFile?.let { addAll(listOf("-F", it)) }
                addAll(commonOptions())
                addAll(listOf("-o", "BatchMode=yes"))
                if (!target.usesSshConfig) {
                    addAll(listOf("-p", target.port.toString()))
                    target.identityFile?.let { addAll(listOf("-i", expandHome(it))) }
                }
            }

    // ssh does not expand ~ in -i: it is a process argument, not a shell word.
    private fun expandHome(path: String): String =
        if (path == "~" || path.startsWith("~/") || path.startsWith("~\\")) {
            System.getProperty("user.home") + path.substring(1)
        } else {
            path
        }

    private fun commonOptions(): List<String> =
        mutableListOf<String>().apply {
            addAll(listOf("-o", "Compression=no")) // the protocol already compresses with zstd
            addAll(listOf("-o", "ConnectTimeout=10"))
            addAll(listOf("-o", "ServerAliveInterval=15"))
            addAll(listOf("-o", "ServerAliveCountMax=3"))
            addAll(listOf("-o", "StrictHostKeyChecking=yes"))
            knownHosts?.let { addAll(listOf("-o", "UserKnownHostsFile=$it")) }
        }

    @Suppress("SwallowedException") // the exit code and error stream describe the failure better
    private fun run(command: String, args: List<String>, stdin: File?, timeoutMs: Long): SshResult {
        val process =
            try {
                ProcessBuilder(args).redirectErrorStream(false).start()
            } catch (e: IOException) {
                throw SshException("Cannot run $command (${e.message})")
            }
        val output = StringBuilder()
        val error = StringBuilder()
        val outThread = pump(process.inputStream, output)
        val errThread = pump(process.errorStream, error)
        try {
            if (stdin != null) {
                stdin.inputStream().use { input -> process.outputStream.use { input.copyTo(it) } }
            } else {
                process.outputStream.close()
            }
        } catch (_: IOException) {
            // The command exited before reading its input: its exit code and error stream say why.
        }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw SshException("$command timed out after ${timeoutMs}ms", error.toString().trim())
        }
        outThread.join(STREAM_JOIN_MS)
        errThread.join(STREAM_JOIN_MS)
        return SshResult(process.exitValue(), output.toString(), error.toString().trim())
    }

    private fun pump(input: java.io.InputStream, sink: StringBuilder): Thread =
        Thread(
            {
                runCatching { input.bufferedReader().use { reader -> sink.append(reader.readText()) } }
            },
            "ssh-output",
        ).apply {
            isDaemon = true
            start()
        }

    private companion object {
        const val STREAM_JOIN_MS = 1_000L
    }
}

/** A running `ssh -N -L`; [errors] keeps what ssh wrote, for the message shown when the link cannot be established. */
class ForwardProcess(private val process: Process) {
    private val error = StringBuilder()
    private val errorThread: Thread

    val errors: String
        @Synchronized get() = error.toString().trim()

    init {
        errorThread =
            Thread(
                {
                    runCatching {
                        process.errorStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                synchronized(this) {
                                    if (error.length < MAX_ERROR) error.appendLine(line)
                                }
                            }
                        }
                    }
                },
                "ssh-tunnel-errors",
            ).apply {
                isDaemon = true
                start()
            }
        // ssh writes nothing on stdout with -N, but a full pipe would block it.
        Thread({ runCatching { process.inputStream.use { it.readBytes() } } }, "ssh-tunnel-output").apply {
            isDaemon = true
            start()
        }
    }

    val isAlive: Boolean get() = process.isAlive

    fun awaitExit(timeoutMs: Long): Int? =
        if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) process.exitValue() else null

    fun close() {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    /** Waits for ssh's last words, so [isFatal] can read them after the process exited. */
    fun awaitErrors(timeoutMs: Long = 300) {
        errorThread.join(timeoutMs)
    }

    /** True when retrying cannot help: the host key, the key or the host name is wrong. */
    fun isFatal(): Boolean {
        val message = errors
        return FATAL.any { message.contains(it, ignoreCase = true) }
    }

    private companion object {
        const val MAX_ERROR = 8 * 1024

        val FATAL =
            listOf(
                "Permission denied",
                "Too many authentication failures",
                "Host key verification failed",
                "REMOTE HOST IDENTIFICATION HAS CHANGED",
                "Could not resolve hostname",
                "No such identity",
                "Load key",
                "Bad configuration option",
            )
    }
}
