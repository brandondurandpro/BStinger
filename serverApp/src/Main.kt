package org.example.kotlineditor.server

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.server.ripgrep.RipgrepSettings
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Standalone server, for remote use: `./kotlin run -m serverApp -- --host 0.0.0.0 --port 7777 --token <secret>`.
 * The token can also come from the KOTLIN_EDITOR_TOKEN environment variable; without one, a random token is printed.
 * `--ripgrep <path>` (or KOTLIN_EDITOR_RIPGREP) chooses the ripgrep executable used by Find in Files.
 */
fun main(args: Array<String>) {
    fun option(name: String) = args.indexOf("--$name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val host = option("host") ?: "127.0.0.1"
    val port = option("port")?.toInt() ?: Protocol.DEFAULT_PORT
    val token = option("token") ?: System.getenv("KOTLIN_EDITOR_TOKEN") ?: EditorServer.newToken()
    val ripgrep = option("ripgrep")?.let { RipgrepSettings(executable = it) } ?: RipgrepSettings()
    val services = LocalServices(ripgrep = ripgrep)
    val server = EditorServer(services, token = token).start(host, port)
    println("Kotlin Editor server listening on $host:${server.port}")
    println("Connect with: --server $host:${server.port} --token $token")
    // Looks ripgrep up (downloading it if needed) now rather than on the first search.
    thread(name = "ripgrep-lookup", isDaemon = true) {
        val binary = runBlocking { services.search.ripgrep() }
        println(binary?.let { "Find in Files: ripgrep ${it.path} (${it.origin})" } ?: "Find in Files: built-in search")
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    CountDownLatch(1).await()
}
