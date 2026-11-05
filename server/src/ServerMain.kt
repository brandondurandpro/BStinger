package org.example.kotlineditor.server

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.server.ripgrep.RipgrepSettings
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Standalone server. Direct use:
 * ```
 * ./kotlin run -m serverApp -- --host 0.0.0.0 --port 7777 --token-file <file>
 * ```
 * Remote development uses it as a daemon, with the secret and the resulting port in files instead of arguments:
 * ```
 * ./kotlin run -m serverApp -- --host 127.0.0.1 --port 0 --token-file token --state-file state.properties
 * ```
 * `--version` prints the version used to identify remote server bundles; `--build` is the id of the installed
 * bundle, copied to the state file so a client can tell whether the running daemon is the bundle it installed.
 * `--data-dir` moves recent projects and sessions (useful for tests and for several accounts on one machine).
 * `--session-grace` is how many seconds a disconnected session keeps its shells and pending answers; remote daemons
 * use a long one so a sleeping laptop does not lose its terminals.
 * `--parent-pid` stops the server when that process ends: the IDE runs its local server as a child process, which
 * must not outlive it, even when the IDE is killed.
 */
object ServerMain {
    /** Entry point of the standalone server: parses the options, listens and waits until the process is stopped. */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = LaunchOptions.from(args)
        if (options.version) {
            println(Protocol.APP_VERSION)
            return
        }
        val fromFile = options.tokenFile?.let(::readToken)
        val given = fromFile ?: options.token ?: System.getenv("KOTLIN_EDITOR_TOKEN")
        val token = given ?: EditorServer.newToken()
        val services =
            LocalServices(
                dataDirectory = options.dataDirectory ?: defaultDataDirectory(),
                ripgrep = options.ripgrep,
            )
        val server =
            EditorServer(
                services,
                token = token,
                options = ServerOptions(sessionGraceMs = options.sessionGraceSeconds * 1_000),
            ).start(options.host, options.port)
        val tracker =
            options.stateFile?.let { state -> ServerState.track(state, server, options.host, token, options.build) }
        // Scripts (measure_memory.sh) read the port from this exact line: the version goes on its own line.
        println("Kotlin Editor server ${Protocol.APP_VERSION}")
        println("Kotlin Editor server listening on ${options.host}:${server.port}")
        if (options.stateFile == null) {
            // The IDE takes the token from a file or the environment: a command line is visible to every user.
            val address = "${options.host}:${server.port}"
            println("Connect with: --server $address and, in KOTLIN_EDITOR_TOKEN or a --token-file, the token:")
            println(token)
        }
        // Looks ripgrep up (downloading it if needed) now rather than on the first search.
        thread(name = "ripgrep-lookup", isDaemon = true) {
            val binary = runBlocking { services.search.ripgrep() }
            println(
                binary?.let { "Find in Files: ripgrep ${it.path} (${it.origin})" } ?: "Find in Files: built-in search",
            )
        }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                tracker?.close()
                options.stateFile?.let(ServerState::removeIfOwned)
                server.close()
            },
        )
        options.parentPid?.let(::exitWithParent)
        CountDownLatch(1).await()
    }

    // Exiting runs the shutdown hook: the state file is removed and the shells are closed.
    private fun exitWithParent(pid: Long) {
        val parent = ProcessHandle.of(pid)
        if (parent.isEmpty) exitProcess(0)
        parent.get().onExit().thenRun { exitProcess(0) }
    }

    private fun defaultDataDirectory(): File = File(System.getProperty("user.home"), ".kotlin-editor")

    private fun readToken(file: File): String =
        try {
            TokenFile.read(file)
        } catch (e: IOException) {
            System.err.println(e.message)
            exitProcess(2)
        }

    /** Options of the command line; parsed apart so the daemon script and the tests can rely on them. */
    internal data class LaunchOptions(
        val host: String,
        val port: Int,
        val token: String?,
        val tokenFile: File?,
        val stateFile: File?,
        val dataDirectory: File?,
        val ripgrep: RipgrepSettings,
        val version: Boolean,
        /** How long a disconnected session (its shells, its answers) waits for the client to come back. */
        val sessionGraceSeconds: Long,
        /** Id of the remote bundle this daemon runs from, or null for a server started by hand. */
        val build: String? = null,
        /** Process whose end stops the server (the IDE that started it), or null. */
        val parentPid: Long? = null,
    ) {
        companion object {
            fun from(args: Array<String>): LaunchOptions {
                fun option(name: String) = args.indexOf("--$name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
                val sessionGrace = option("session-grace")?.toLongOrNull() ?: ServerOptions().sessionGraceMs / 1_000
                return LaunchOptions(
                    host = option("host") ?: "127.0.0.1",
                    port = option("port")?.toInt() ?: Protocol.DEFAULT_PORT,
                    token = option("token"),
                    tokenFile = option("token-file")?.let(::File),
                    stateFile = option("state-file")?.let(::File),
                    dataDirectory = option("data-dir")?.let(::File),
                    ripgrep = option("ripgrep")?.let { RipgrepSettings(executable = it) } ?: RipgrepSettings(),
                    version = args.contains("--version"),
                    sessionGraceSeconds = sessionGrace,
                    build = option("build")?.takeIf { it.isNotBlank() },
                    parentPid = option("parent-pid")?.toLongOrNull(),
                )
            }
        }
    }
}
