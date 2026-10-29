package org.example.kotlineditor.server

import org.example.kotlineditor.rpc.Protocol
import java.util.concurrent.CountDownLatch

/**
 * Standalone server, for remote use: `./kotlin run -m serverApp -- --host 0.0.0.0 --port 7777 --token <secret>`.
 * The token can also come from the KOTLIN_EDITOR_TOKEN environment variable; without one, a random token is printed.
 */
fun main(args: Array<String>) {
    fun option(name: String) = args.indexOf("--$name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val host = option("host") ?: "127.0.0.1"
    val port = option("port")?.toInt() ?: Protocol.DEFAULT_PORT
    val token = option("token") ?: System.getenv("KOTLIN_EDITOR_TOKEN") ?: EditorServer.newToken()
    val server = EditorServer(token = token).start(host, port)
    println("Kotlin Editor server listening on $host:${server.port}")
    println("Connect with: --server $host:${server.port} --token $token")
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    CountDownLatch(1).await()
}
