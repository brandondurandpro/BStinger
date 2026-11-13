package org.example.kotlineditor.server

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The server of the IDE on its own machine, on loopback, with a random token.
 *
 * It runs in a child process, so that its memory spikes, GC pauses and failures (an `OutOfMemoryError`) do not
 * reach the UI: the server compiled to a native binary when there is one ([Settings.binary]: less memory, no
 * warm-up), a child JVM otherwise ([ServerMain], from the classes the IDE already has). When the child dies it is
 * started again on the same port with the same token: the client reconnects by itself, as to a remote daemon that
 * restarted. The child exits with the IDE, even when the IDE is killed (`--parent-pid`).
 *
 * Where no `java` launcher can be found (a packaged application image: jpackage ships the runtime without its
 * commands), or when the child never manages to start, the server runs in the IDE's JVM instead, after a warning.
 */
class LocalServer private constructor(
    /** Port the client connects to; it stays the same across restarts of the child. */
    val port: Int,
    /** Secret of the handshake, passed to the child through a file readable by its owner only. */
    val token: String,
    private val settings: Settings,
) : Closeable {
    /** How to start the child; a null [java] runs the server in this JVM. */
    class Settings(
        val java: String? = javaLauncher(),
        /**
         * The server compiled to a native binary (`./native-server build`), started rather than a JVM. It cannot
         * load the jar of a plugin: null as soon as a server plugin is installed.
         */
        val binary: String? = nativeBinary(),
        /** Class path of the child: the IDE's own, which holds the server. */
        val classpath: String = System.getProperty("java.class.path"),
        /** More options of [ServerMain] (`--data-dir`, `--ripgrep`…). */
        val serverArguments: List<String> = emptyList(),
        /** The services of a server run in this JVM. */
        val services: () -> LocalServices = { LocalServices() },
        /** Where the warnings go (a server run in this JVM, a child started again). */
        val warn: (String) -> Unit = { System.err.println("Local server: $it") },
        /** Starts in a row that end before the server listens, after which it runs in this JVM. */
        val maxFailedStarts: Int = MAX_FAILED_STARTS,
    )

    private val directoryHolder = lazy { privateDirectory() }
    private val directory: Path by directoryHolder
    private val tokenFile: Path get() = directory.resolve("token")
    private val stateFile: Path get() = directory.resolve("state.properties")
    private val argumentFile: Path get() = directory.resolve("server.args")

    // The options of the server, the same for a native binary and for a JVM.
    private val serverArguments: List<String> by lazy {
        listOf("--host", LOOPBACK, "--port", port.toString()) +
            listOf("--token-file", tokenFile.toString(), "--state-file", stateFile.toString()) +
            listOf("--parent-pid", ProcessHandle.current().pid().toString()) +
            settings.serverArguments
    }

    @Volatile private var closed = false

    @Volatile private var child: Process? = null

    @Volatile private var embedded: EditorServer? = null

    @Volatile private var supervisor: Thread? = null

    /** Process id of the running child, or null when the server runs in this JVM or is between two starts. */
    val childPid: Long? get() = child?.takeIf { it.isAlive }?.pid()

    /** Whether the server runs in the IDE's JVM (no child could be started). */
    val inProcess: Boolean get() = embedded != null

    /** Waits until the server listens (the child wrote its state file, or the server runs in this JVM). */
    fun awaitListening(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            val running = child
            val childListens = running != null && running.isAlive && listeningPid() == running.pid()
            if (inProcess || childListens) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    override fun close() {
        closed = true
        supervisor?.interrupt()
        supervisor?.join(STOP_TIMEOUT_MS)
        child?.let(::stopProcess)
        embedded?.close()
        if (directoryHolder.isInitialized()) directory.toFile().deleteRecursively()
    }

    private fun startChild(command: List<String>) {
        TokenFile.write(tokenFile, token)
        val first = launchOrFallBack(command) ?: return
        supervisor = thread(name = "local-server-supervisor", isDaemon = true) { supervise(command, first) }
    }

    // Starts the child again whenever it dies, until the IDE closes; gives up after starts that never listened.
    private fun supervise(command: List<String>, first: Process) {
        var process: Process? = first
        var failedStarts = 0
        var backoff = MIN_RESTART_DELAY_MS
        while (process != null && !closed) {
            val listened = waitUntilListeningOrDead(process)
            val exit = waitForExit(process)
            if (listened) backoff = MIN_RESTART_DELAY_MS
            failedStarts = if (listened) 0 else failedStarts + 1
            val gaveUp = failedStarts >= settings.maxFailedStarts
            if (gaveUp) fallBack("the server process stopped $failedStarts times while starting")
            val restart = exit != null && !closed && !gaveUp
            process = if (restart) restartAfter(backoff, exit, command) else null
            backoff = minOf(backoff * 2, MAX_RESTART_DELAY_MS)
        }
    }

    private fun restartAfter(delayMs: Long, exit: Int?, command: List<String>): Process? {
        settings.warn("the server process stopped (exit code $exit): starting it again")
        val waited = sleepInterruptibly(delayMs) && !closed
        return if (waited) launchOrFallBack(command) else null
    }

    private fun launchOrFallBack(command: List<String>): Process? {
        val started =
            try {
                ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.from(nullFile()))
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
            } catch (e: IOException) {
                fallBack("cannot start ${command.firstOrNull()} (${e.message})")
                null
            }
        if (started != null) child = started
        if (started != null && closed) stopProcess(started)
        return started
    }

    private fun fallBack(reason: String) {
        if (closed) return
        settings.warn("$reason: the server runs in the IDE's JVM")
        embedded = EditorServer(settings.services(), token).start(LOOPBACK, port)
    }

    private fun waitUntilListeningOrDead(process: Process): Boolean {
        var listening = listeningPid() == process.pid()
        var waiting = !listening && process.isAlive
        while (waiting && !closed) {
            val slept = sleepInterruptibly(POLL_MS)
            listening = listeningPid() == process.pid()
            waiting = slept && !listening && process.isAlive
        }
        return listening
    }

    // Pid of the child that wrote the state file: that one listens.
    private fun listeningPid(): Long? =
        ServerState
            .read(stateFile.toFile())
            ?.getProperty("pid")
            ?.toLongOrNull()

    // The command of the child: the native binary with the options of the server, or `java @file`.
    private fun childCommand(java: String?): List<String>? {
        val binary = settings.binary
        if (binary != null) return listOf(binary) + serverArguments
        return java?.let {
            writeArguments()
            listOf(it, "@$argumentFile")
        }
    }

    // In a file rather than on the command line: a long class path does not fit a Windows command line.
    private fun writeArguments() {
        val jvm = listOf("--enable-native-access=ALL-UNNAMED", "-cp", settings.classpath, ServerMain::class.java.name)
        val arguments = jvm + serverArguments
        Files.writeString(argumentFile, arguments.joinToString("\n", postfix = "\n") { quoted(it) })
    }

    /** Starting the local server. */
    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val POLL_MS = 20L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val MIN_RESTART_DELAY_MS = 200L
        private const val MAX_RESTART_DELAY_MS = 5_000L
        private const val MAX_FAILED_STARTS = 3
        private const val BINARY_PROPERTY = "kotlin.editor.server.binary"
        private const val BINARY_VARIABLE = "KOTLIN_EDITOR_SERVER_BINARY"

        /**
         * Starts the server: a child process when [Settings.binary] or [Settings.java] is known, in this JVM
         * otherwise. Returns without
         * waiting for the child: the client connects (and retries) while it starts.
         */
        fun start(settings: Settings = Settings()): LocalServer {
            val token = EditorServer.newToken()
            val hasLauncher = settings.binary != null || settings.java != null
            if (!hasLauncher) {
                settings.warn("no java launcher in the IDE's runtime: the server runs in the IDE's JVM")
                val embedded = EditorServer(settings.services(), token).start(LOOPBACK, 0)
                return LocalServer(embedded.port, token, settings).also { it.embedded = embedded }
            }
            val server = LocalServer(freePort(), token, settings)
            server.childCommand(settings.java)?.let(server::startChild)
            return server
        }

        /**
         * The `java` command of the running JVM: `bin/java` of its runtime, or the command that started it when it
         * is `java`. Null for a packaged application, whose runtime has no commands and whose launcher is its own.
         */
        fun javaLauncher(): String? {
            val name = if (isWindows()) "java.exe" else "java"
            val inRuntime = File(System.getProperty("java.home"), "bin/$name")
            val command =
                ProcessHandle
                    .current()
                    .info()
                    .command()
                    .orElse(null)
                    ?.let(::File)
            val commandIsJava = command != null && command.name == name && command.canExecute()
            return when {
                inRuntime.canExecute() -> inRuntime.path
                commandIsJava -> command.path
                else -> null
            }
        }

        /**
         * The native server to start: the file named by the `kotlin.editor.server.binary` system property or the
         * `KOTLIN_EDITOR_SERVER_BINARY` environment variable, when it can be executed and no server plugin is
         * installed in [pluginsDirectory] (a native binary cannot load their jars).
         */
        fun nativeBinary(
            configured: String? = System.getProperty(BINARY_PROPERTY) ?: System.getenv(BINARY_VARIABLE),
            pluginsDirectory: File = File(System.getProperty("user.home"), ".kotlin-editor/server-plugins"),
        ): String? {
            val binary = configured?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
            val installed = pluginsDirectory.listFiles { file -> file.isDirectory }.orEmpty()
            val usable = binary.isFile && binary.canExecute() && installed.isEmpty()
            return binary.path.takeIf { usable }
        }

        private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

        private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        private fun nullFile(): File = File(if (isWindows()) "NUL" else "/dev/null")

        // The temporary directory of the token and the state file: its owner's only.
        private fun privateDirectory(): Path =
            try {
                val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
                Files.createTempDirectory("kotlin-editor-server", ownerOnly)
            } catch (_: UnsupportedOperationException) {
                // Windows: the user's temporary directory is already private.
                Files.createTempDirectory("kotlin-editor-server")
            }

        // A `java @file` argument: quoted, its backslashes and quotes escaped (Windows paths have backslashes).
        private fun quoted(argument: String): String {
            val escaped = argument.replace("\\", "\\\\").replace("\"", "\\\"")
            return "\"$escaped\""
        }
    }
}

// The exit code of [process], or null when the waiting thread is interrupted (the IDE closes).
private fun waitForExit(process: Process): Int? =
    try {
        process.waitFor()
    } catch (_: InterruptedException) {
        null
    }

// False when interrupted (the IDE closes).
private fun sleepInterruptibly(millis: Long): Boolean =
    try {
        Thread.sleep(millis)
        true
    } catch (_: InterruptedException) {
        false
    }

private const val STOP_TIMEOUT_MILLIS = 5_000L

// SIGTERM first, so that the child closes its shells and removes its state file; then SIGKILL.
private fun stopProcess(process: Process) {
    process.destroy()
    val stopped = runCatching { process.waitFor(STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    if (!stopped) process.destroyForcibly()
}
