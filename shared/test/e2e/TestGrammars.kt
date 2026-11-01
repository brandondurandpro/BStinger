package org.example.kotlineditor.e2e

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.example.kotlineditor.syntax.treesitter.GrammarSpec
import org.example.kotlineditor.syntax.treesitter.GrammarStore
import org.example.kotlineditor.syntax.treesitter.MavenGrammarRepository
import org.example.kotlineditor.syntax.treesitter.NativePlatform
import java.io.File
import java.net.InetSocketAddress
import java.net.JarURLConnection
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Maven repository on loopback serving the grammar jars of the test classpath (the `tree-sitter-*` test
 * dependencies of `shared`), so that tests download grammars as the IDE does, without reaching Maven Central. A chaos
 * test can take it down, slow it down, or make it answer a page instead of the jar (a captive portal).
 */
class LocalGrammarRepository : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** Connections are closed without an answer, as when the network is down. */
    @Volatile
    var down = false

    /** Delay before each answer. */
    @Volatile
    var latencyMs = 0L

    /** Answers an HTML page with status 200 instead of the jar. */
    @Volatile
    var captivePortal = false

    private val served = AtomicInteger()

    /** Jars served. */
    val jarsServed: Int get() = served.get()

    val url: String

    init {
        server.executor = Executors.newCachedThreadPool { Thread(it, "grammar-repository").apply { isDaemon = true } }
        server.createContext("/") { exchange -> exchange.use { answer(it) } }
        server.start()
        url = "http://127.0.0.1:${server.address.port}/maven2"
    }

    private fun answer(exchange: HttpExchange) {
        if (latencyMs > 0) Thread.sleep(latencyMs)
        if (down) return
        if (captivePortal) {
            send(exchange, "<html><body>Sign in to the Wi-Fi</body></html>".toByteArray())
            return
        }
        val artifact =
            exchange.requestURI.path
                .substringAfterLast('/')
                .removeSuffix(".jar")
        val spec = GrammarSpec.all.firstOrNull { "${it.artifact}-${it.version}" == artifact }
        val jar = spec?.let(::jarOf)
        if (jar == null) {
            exchange.sendResponseHeaders(NOT_FOUND, -1)
        } else {
            send(exchange, jar)
            served.incrementAndGet()
        }
    }

    private fun send(exchange: HttpExchange, body: ByteArray) {
        exchange.sendResponseHeaders(OK, body.size.toLong())
        exchange.responseBody.write(body)
    }

    override fun close() = server.stop(0)

    companion object {
        private const val OK = 200
        private const val NOT_FOUND = 404

        /** The jar of [spec] if it is on the test classpath. */
        fun jarOf(spec: GrammarSpec): ByteArray? {
            val entry = NativePlatform.current?.let(spec::libraryEntry)
            val url = entry?.let { LocalGrammarRepository::class.java.classLoader.getResource(it) }
            val connection = url?.openConnection() as? JarURLConnection
            return connection?.let { File(it.jarFileURL.toURI()).readBytes() }
        }
    }
}

/** Grammars of the tests: downloaded from a [LocalGrammarRepository], cached in a temporary directory. */
object TestGrammars {
    /** Retries come quickly, so that a chaos test sees a download succeed once the repository is back. */
    const val RETRY_DELAY_MS = 100L

    private val repository by lazy { LocalGrammarRepository() }

    /** One store for the e2e tests that only use the grammars: each jar is downloaded once per test run. */
    val shared: GrammarStore by lazy { store(repository) }

    /** A store of its own, with an empty cache unless [directory] is given. */
    fun store(
        repository: LocalGrammarRepository,
        directory: File = Files.createTempDirectory("kotlin-editor-grammars").toFile(),
        platform: NativePlatform? = NativePlatform.current,
    ): GrammarStore {
        Runtime.getRuntime().addShutdownHook(Thread { directory.deleteRecursively() })
        return GrammarStore(
            directory,
            MavenGrammarRepository(repository.url),
            platform,
            firstRetryDelayMs = RETRY_DELAY_MS,
            maxRetryDelayMs = RETRY_DELAY_MS,
        )
    }
}
