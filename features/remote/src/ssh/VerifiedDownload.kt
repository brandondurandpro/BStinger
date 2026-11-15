package org.example.kotlineditor.remote

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A transfer that failed; the message says what, in words for the user. */
class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** One file of a release to download: [name] at [baseUrl], kept in [directory]. */
data class DownloadRequest(
    val baseUrl: String,
    val name: String,
    val directory: File,
    /** What to tell when [url] does not exist (HTTP 404). */
    val missing: (url: String) -> String = { "$it does not exist." },
)

/**
 * Downloads a file of a GitHub release and keeps it in a directory only once it matches the release's
 * `SHA256SUMS.txt`: the bytes go to a `.part` file, hashed on the way, and are moved into place when the checksum is
 * right. A file that is already there is a verified one, and is reused.
 *
 * Cancelling the thread (an interrupt, which is how a cancelled coroutine stops a blocking call) stops the transfer
 * and deletes the `.part` file. The JDK's request timeout only covers the wait for the response, not the body, so a
 * body that stops arriving is cut by a watchdog after [stallMillis] without a byte: a slow link may take as long as it
 * needs, a dead one fails.
 */
class VerifiedDownload(private val stallMillis: Long = STALL_MILLIS, private val client: HttpClient = sharedClient) {
    /**
     * The verified file, downloaded if it is not kept yet; [onProgress] gets the bytes received and the total (-1
     * when the server does not tell it), at most every [PROGRESS_INTERVAL_MILLIS].
     */
    fun fetch(request: DownloadRequest, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): File {
        val kept = File(request.directory, request.name)
        if (kept.isFile) return kept
        val directory = request.directory.toPath()
        Files.createDirectories(directory)
        deleteStalePartials(directory)
        val expected = expectedHash(request)
        val partial = Files.createTempFile(directory, request.name, PART_SUFFIX)
        try {
            val actual = download(request, partial, onProgress)
            if (actual != expected) {
                throw DownloadException(
                    "The downloaded ${request.name} is corrupted (checksum $actual, expected $expected).",
                )
            }
            Files.move(partial, kept.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(partial)
        }
        return kept
    }

    // The line of SHA256SUMS.txt naming the file: `<hash>  <name>`.
    private fun expectedHash(request: DownloadRequest): String {
        val sums = sendText(request, "${request.baseUrl}/$SUMS_NAME")
        return checksumOf(sums, request.name)
            ?: throw DownloadException("$SUMS_NAME of the release has no checksum for ${request.name}.")
    }

    // The sums are read like the file, under the watchdog: a body that goes silent must not hang the update.
    private fun sendText(request: DownloadRequest, url: String): String {
        val response = send(request, url, HttpResponse.BodyHandlers.ofInputStream())
        return guarded(url) { guard ->
            response.body().use { input ->
                val bytes = input.readNBytes(MAX_TEXT_BYTES)
                guard.progressed()
                String(bytes, Charsets.UTF_8)
            }
        }
    }

    // Runs [body] under a watchdog that interrupts it after [stallMillis] without progress.
    private fun <T> guarded(url: String, body: (StallGuard) -> T): T {
        val guard = StallGuard(stallMillis, Thread.currentThread())
        try {
            return body(guard)
        } catch (e: IOException) {
            throw stalledOr(e, guard, url)
        } catch (e: InterruptedException) {
            throw stalledOr(e, guard, url)
        } finally {
            guard.close()
        }
    }

    // Streams the body to [target] and returns its SHA-256, computed on the way.
    private fun download(request: DownloadRequest, target: Path, onProgress: (Long, Long) -> Unit): String {
        val url = "${request.baseUrl}/${request.name}"
        val response = send(request, url, HttpResponse.BodyHandlers.ofInputStream())
        val total = response.headers().firstValueAsLong("Content-Length").orElse(UNKNOWN_SIZE)
        val digest = MessageDigest.getInstance("SHA-256")
        guarded(url) { guard ->
            response.body().use { input ->
                requireRoom(request.directory, total)
                Files.newOutputStream(target).use { output -> copy(input, output, digest, total, guard, onProgress) }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copy(
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
        total: Long,
        guard: StallGuard,
        onProgress: (Long, Long) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var done = 0L
        var lastReport = 0L
        while (true) {
            if (Thread.interrupted()) throw InterruptedIOException("Download cancelled")
            val read = input.read(buffer)
            if (read < 0) break
            guard.progressed()
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            done += read
            val now = System.nanoTime()
            if (now - lastReport >= PROGRESS_INTERVAL_MILLIS * NANOS_PER_MILLI) {
                lastReport = now
                onProgress(done, total)
            }
        }
        onProgress(done, total)
    }

    // The watchdog's interrupt is a stall; any other failure (or a cancellation by the caller) goes on as it is.
    private fun stalledOr(failure: Exception, guard: StallGuard, url: String): Exception {
        if (!guard.stopped()) return failure
        return DownloadException(
            "Cannot download $url: nothing received for ${stallMillis / MILLIS_PER_SECOND} s.",
            failure,
        )
    }

    private fun requireRoom(directory: File, total: Long) {
        if (total <= 0) return
        val free = directory.usableSpace
        if (free < total + SPACE_MARGIN) {
            throw DownloadException(
                "Not enough disk space in $directory: ${megabytes(total)} MB to download, ${megabytes(free)} MB free.",
            )
        }
    }

    @Suppress("ThrowsCount") // each failure of the transfer says what could not be downloaded
    private fun <T> send(request: DownloadRequest, url: String, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val http =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(RESPONSE_SECONDS))
                .GET()
                .build()
        val response =
            try {
                client.send(http, handler)
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                throw DownloadException("Cannot download $url: ${e.message ?: e.javaClass.simpleName}", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Download cancelled")
            }
        checkStatus(request, url, response.statusCode())
        return response
    }

    private fun checkStatus(request: DownloadRequest, url: String, status: Int) {
        if (status == HTTP_NOT_FOUND) throw DownloadException(request.missing(url))
        if (status != HTTP_OK) throw DownloadException("Cannot download $url: HTTP $status.")
    }

    // A `.part` file nobody has written for an hour is the leftover of a crash.
    private fun deleteStalePartials(directory: Path) {
        val limit = System.currentTimeMillis() - STALE_PART_MILLIS
        val files = directory.toFile().listFiles { file -> file.name.endsWith(PART_SUFFIX) } ?: return
        files.filter { it.lastModified() < limit }.forEach { it.delete() }
    }

    companion object {
        const val SUMS_NAME = "SHA256SUMS.txt"
        const val PART_SUFFIX = ".part"

        /** How long the body of a download may stay silent before it is cut. */
        const val STALL_MILLIS = 60_000L
        const val PROGRESS_INTERVAL_MILLIS = 100L
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_TEXT_BYTES = 1024 * 1024
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val RESPONSE_SECONDS = 120L
        private const val HTTP_OK = 200
        private const val HTTP_NOT_FOUND = 404
        private const val UNKNOWN_SIZE = -1L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val SPACE_MARGIN = 64L * 1024L * 1024L
        private const val STALE_PART_MILLIS = 60L * 60L * MILLIS_PER_SECOND

        private val sharedClient: HttpClient =
            HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build()

        /** The checksum [sums] (the text of `SHA256SUMS.txt`) gives for [name]; null when it has none. */
        fun checksumOf(sums: String, name: String): String? {
            val lines = sums.lines().map { it.trim().split(Regex("\\s+"), limit = 2) }
            val line = lines.firstOrNull { it.size == 2 && it[1].trimStart('*') == name }
            return line?.get(0)?.lowercase()
        }
    }
}

private const val BYTES_PER_MEGABYTE = 1024L * 1024L

private fun megabytes(bytes: Long): Long = bytes / BYTES_PER_MEGABYTE

/** Interrupts [reader] when [progressed] was not called for [timeoutMillis]; [stopped] tells whether it did. */
private class StallGuard(private val timeoutMillis: Long, private val reader: Thread) : AutoCloseable {
    private val timer =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(
                task,
                "download-watchdog",
            ).apply { isDaemon = true }
        }

    @Volatile private var lastProgress = System.nanoTime()
    private var tripped = false
    private var closed = false

    init {
        val period = (timeoutMillis / CHECKS).coerceAtLeast(MIN_PERIOD_MILLIS)
        timer.scheduleWithFixedDelay(::check, period, period, TimeUnit.MILLISECONDS)
    }

    fun progressed() {
        lastProgress = System.nanoTime()
    }

    /** Whether the watchdog interrupted the reader. */
    @Synchronized
    fun stopped(): Boolean = tripped

    @Synchronized
    private fun check() {
        val silentMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastProgress)
        val alreadyDone = closed || tripped
        if (alreadyDone || silentMillis < timeoutMillis) return
        tripped = true
        reader.interrupt()
    }

    // Once closed the watchdog can no longer interrupt; an interrupt it already sent is cleared.
    @Synchronized
    override fun close() {
        closed = true
        timer.shutdownNow()
        if (tripped) Thread.interrupted()
    }

    private companion object {
        const val CHECKS = 4L
        const val MIN_PERIOD_MILLIS = 10L
    }
}
