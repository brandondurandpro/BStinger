package org.example.kotlineditor.syntax.treesitter

import io.github.treesitter.jtreesitter.Language
import io.github.treesitter.jtreesitter.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.SymbolLookup
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/** Where grammar jars are downloaded from. */
fun interface GrammarRepository {
    /** The jar of [spec]; throws an [IOException] when it cannot be had now (offline, server down...). */
    suspend fun download(spec: GrammarSpec): ByteArray
}

/** Maven Central, or a mirror with the same layout at [baseUrl]. */
class MavenGrammarRepository(
    private val baseUrl: String = "https://repo1.maven.org/maven2",
    private val timeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
) : GrammarRepository {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    override suspend fun download(spec: GrammarSpec): ByteArray {
        val directory = "${baseUrl.trimEnd('/')}/io/github/bonede/${spec.artifact}/${spec.version}"
        val url = "$directory/${spec.artifact}-${spec.version}.jar"
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).await()
        if (response.statusCode() != HTTP_OK) throw IOException("$url: HTTP ${response.statusCode()}")
        return response.body()
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 30L
        const val HTTP_OK = 200
    }
}

/** A grammar ready to parse with, and its highlight query. */
class LoadedGrammar internal constructor(val spec: GrammarSpec, val language: Language, val query: Query) {
    private val styles: Map<String, Int> = query.captureNames.associateWith(CaptureStyles::styleOf)

    /** Style of the capture named [name] (see [CaptureStyles.styleOf]). */
    internal fun styleOf(name: String): Int = styles[name] ?: CaptureStyles.IGNORED
}

/**
 * The grammars of the IDE: each is downloaded the first time a file of its language opens, checked against the
 * catalog's SHA-256, and its library kept in [directory] for the next times. Downloads that fail are retried with a
 * growing delay (up to [maxRetryDelayMs]) while a file still waits for the grammar; until then it keeps the line lexer.
 */
class GrammarStore(
    private val directory: File,
    private val repository: GrammarRepository = MavenGrammarRepository(),
    private val platform: NativePlatform? = NativePlatform.current,
    private val firstRetryDelayMs: Long = FIRST_RETRY_DELAY_MS,
    private val maxRetryDelayMs: Long = MAX_RETRY_DELAY_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A completed load is kept: its grammar, or null when it can never load here (no library for this platform).
    private val loads = ConcurrentHashMap<String, Deferred<LoadedGrammar?>>()

    private val downloaded = AtomicInteger()

    /** Number of jars downloaded (cached libraries are not downloaded again). */
    val downloads: Int get() = downloaded.get()

    /** Why the last grammar that could not load did not, for diagnostics; `null` while every one loaded. */
    @Volatile
    var lastFailure: String? = null
        private set

    /** The grammar of [spec], once it is available; `null` if it never will be here. Suspends while it downloads. */
    suspend fun await(spec: GrammarSpec): LoadedGrammar? {
        var retryDelay = firstRetryDelayMs
        while (true) {
            val load = loads.computeIfAbsent(spec.id) { scope.async { loadOrNull(spec) } }
            try {
                return load.await()
            } catch (_: IOException) {
                loads.remove(spec.id, load)
                delay(retryDelay)
                retryDelay = minOf(retryDelay * 2, maxRetryDelayMs)
            }
        }
    }

    // Only an IOException is worth retrying: anything else would fail again.
    private suspend fun loadOrNull(spec: GrammarSpec): LoadedGrammar? =
        try {
            load(spec)
        } catch (e: IOException) {
            throw failed(spec, e)
        } catch (e: IllegalStateException) {
            failed(spec, e).let { null }
        } catch (e: IllegalArgumentException) {
            // A library that does not load, or a query it rejects (QueryError).
            failed(spec, e).let { null }
        } catch (e: LinkageError) {
            failed(spec, e).let { null }
        } catch (_: RuntimeException) {
            lastFailure = "$spec: unexpected error"
            null
        }

    private fun <T : Throwable> failed(spec: GrammarSpec, error: T): T {
        lastFailure = "$spec: " + generateSequence<Throwable>(error) { it.cause }.joinToString(" <- ")
        return error
    }

    private suspend fun load(spec: GrammarSpec): LoadedGrammar {
        val platform =
            checkNotNull(platform?.takeIf { it.id in spec.platforms }) { "no library for platform ${platform?.id}" }
        TreeSitterCore.directory = directory
        TreeSitterCore.failure?.let { throw IllegalStateException("the tree-sitter runtime does not load", it) }
        val library = File(directory, "grammars/${spec.artifact}-${spec.version}/${spec.libraryEntry(platform)}")
        if (!library.isFile) download(spec, platform, library)
        return open(spec, library)
    }

    private suspend fun download(spec: GrammarSpec, platform: NativePlatform, library: File) {
        val jar = repository.download(spec)
        downloaded.incrementAndGet()
        val sha = MessageDigest.getInstance("SHA-256").digest(jar).joinToString("") { "%02x".format(it) }
        if (sha != spec.sha256) throw IOException("$spec: unexpected SHA-256 $sha")
        val bytes = checkNotNull(entry(jar, spec.libraryEntry(platform))) { "$spec has no ${platform.id} library" }
        writeAtomically(library, bytes)
    }

    private fun open(spec: GrammarSpec, library: File): LoadedGrammar {
        val text =
            checkNotNull(GrammarStore::class.java.getResourceAsStream("/tree-sitter/${spec.id}/highlights.scm")) {
                "no highlight query"
            }.use { it.readBytes().decodeToString() }
        val language = Language.load(SymbolLookup.libraryLookup(library.toPath(), Arena.global()), spec.symbol)
        return LoadedGrammar(spec, language, Query(language, text))
    }

    private fun entry(jar: ByteArray, name: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(jar)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == name) return zip.readBytes()
            }
        }
    }

    private companion object {
        const val FIRST_RETRY_DELAY_MS = 2_000L
        const val MAX_RETRY_DELAY_MS = 60_000L
    }
}
