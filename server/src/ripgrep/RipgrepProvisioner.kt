package org.example.kotlineditor.server.ripgrep

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.time.Duration.Companion.seconds

/** Where the server finds ripgrep, and how long a search may run. */
data class RipgrepSettings(
    /**
     * Explicit ripgrep executable (`--ripgrep <path>` or `KOTLIN_EDITOR_RIPGREP`). When it is set but unusable, no
     * other copy is tried: the built-in search takes over, so a wrong setting shows instead of being worked around.
     */
    val executable: String? = System.getenv(ENVIRONMENT_VARIABLE)?.takeIf { it.isNotBlank() },
    /** Directory holding the copy shipped with the server distribution, or null when there is none. */
    val bundledDirectory: Path? = defaultBundledDirectory(),
    /** Root of the tools downloaded on first use: ripgrep goes to `ripgrep/<version>/` inside. */
    val toolsDirectory: Path = Paths.get(System.getProperty("user.home"), ".kotlin-editor", "tools"),
    /** Whether a verified copy may be downloaded when no other one is available. */
    val download: Boolean = true,
    /** Longest a single search may run: the results found by then are returned. */
    val timeout: kotlin.time.Duration = 30.seconds,
) {
    /** Default locations. */
    companion object {
        /** Environment variable naming an explicit ripgrep executable. */
        const val ENVIRONMENT_VARIABLE = "KOTLIN_EDITOR_RIPGREP"

        /**
         * Packaging hook: a server distribution ships ripgrep as `ripgrep/rg` (`rg.exe` on Windows) next to the
         * jar holding the server classes. The copy must be the pinned release ([RipgrepRelease]): it is verified
         * like a downloaded one. No packaging step does it yet, so this directory is usually absent.
         */
        fun defaultBundledDirectory(): Path? =
            runCatching {
                val jar = RipgrepSettings::class.java.protectionDomain.codeSource.location
                Paths.get(jar.toURI()).parent?.resolve("ripgrep")
            }.getOrNull()
    }
}

/** A usable ripgrep executable, and where it was found. */
data class RipgrepBinary(
    /** The executable. */
    val path: Path,
    /** How it was obtained. */
    val origin: Origin,
) {
    /** Lookup step that found the executable, in lookup order. */
    enum class Origin {
        /** `--ripgrep` / `KOTLIN_EDITOR_RIPGREP`. */
        Configured,

        /** Shipped with the server distribution. */
        Bundled,

        /** Downloaded (now or on an earlier run) and verified. */
        Downloaded,
    }
}

/**
 * Finds ripgrep, in order: the configured executable, the copy bundled with the server, the verified copy
 * downloaded earlier into `<tools>/ripgrep/<version>/`, then a new verified download. Null means the built-in
 * search must be used. Blocking: call it on an IO thread.
 */
internal class RipgrepProvisioner(
    private val settings: RipgrepSettings,
    private val platform: RipgrepPlatform? = RipgrepPlatform.current(),
    private val fetch: (String) -> InputStream = ::httpGet,
) {
    /** Directory of the downloaded copy. */
    val cacheDirectory: Path = settings.toolsDirectory.resolve("ripgrep").resolve(RipgrepRelease.VERSION)

    fun locate(): RipgrepBinary? {
        val configured = settings.executable
        return if (configured != null) configured(configured) else platform?.let { bundled(it) ?: downloaded(it) }
    }

    private fun configured(executable: String): RipgrepBinary? =
        runCatching { Paths.get(executable) }
            .getOrNull()
            ?.takeIf(::runs)
            ?.let { RipgrepBinary(it, RipgrepBinary.Origin.Configured) }

    private fun bundled(platform: RipgrepPlatform): RipgrepBinary? =
        settings.bundledDirectory
            ?.resolve(platform.executableName)
            ?.takeIf { isVerified(it, platform) && runs(it) }
            ?.let { RipgrepBinary(it, RipgrepBinary.Origin.Bundled) }

    private fun downloaded(platform: RipgrepPlatform): RipgrepBinary? {
        val cached = cacheDirectory.resolve(platform.executableName)
        val present = isVerified(cached, platform) || tryDownload(platform, cached)
        return cached.takeIf { present && runs(it) }?.let { RipgrepBinary(it, RipgrepBinary.Origin.Downloaded) }
    }

    // False when downloads are disabled, offline, or when the download is not the pinned release.
    private fun tryDownload(platform: RipgrepPlatform, target: Path): Boolean =
        settings.download &&
            try {
                download(platform, target)
                true
            } catch (_: IOException) {
                false
            }

    // Archive downloaded to a temporary file, verified, then the executable extracted, verified and moved in place
    // atomically: an interrupted or concurrent download never leaves a half-written `rg` behind.
    private fun download(platform: RipgrepPlatform, target: Path) {
        Files.createDirectories(cacheDirectory)
        val archive = Files.createTempFile(cacheDirectory, "archive-", ".part")
        val executable = Files.createTempFile(cacheDirectory, "rg-", ".part")
        try {
            val archiveHash =
                fetch(RipgrepRelease.archiveUrl(platform)).use { input ->
                    Files.newOutputStream(archive).use { output -> copyHashing(input, output, MAX_ARCHIVE_SIZE) }
                }
            ensure(archiveHash == platform.archiveSha256) { "Checksum mismatch for ${platform.archiveName}" }
            val found =
                Files.newInputStream(archive).use { input ->
                    Files.newOutputStream(executable).use { output -> extract(input, platform, output) }
                }
            ensure(found) { "${platform.archiveEntry} missing from ${platform.archiveName}" }
            ensure(sha256(executable) == platform.binarySha256) { "Checksum mismatch for ${platform.archiveEntry}" }
            executable.toFile().setExecutable(true)
            moveInPlace(executable, target)
        } finally {
            Files.deleteIfExists(archive)
            Files.deleteIfExists(executable)
        }
    }

    private fun moveInPlace(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            // Another server may have installed the same verified file meanwhile (and may be running it).
            if (!isVerified(target, checkNotNull(platform))) throw e
        }
    }

    private fun extract(input: InputStream, platform: RipgrepPlatform, output: OutputStream): Boolean =
        if (platform.archiveName.endsWith(".zip")) {
            val zip = ZipInputStream(input)
            generateSequence { zip.nextEntry }
                .firstOrNull { it.name == platform.archiveEntry }
                ?.let { copyHashing(zip, output, MAX_EXECUTABLE_SIZE) } != null
        } else {
            Tar.extract(GZIPInputStream(input), platform.archiveEntry, output)
        }

    private fun isVerified(path: Path, platform: RipgrepPlatform): Boolean =
        Files.isRegularFile(path) && runCatching { sha256(path) }.getOrNull() == platform.binarySha256

    /** Minimal tar reader: the release archives are plain ustar files whose entry names fit the 100-byte field. */
    private object Tar {
        private const val BLOCK = 512
        private const val NAME_LENGTH = 100
        private const val SIZE_OFFSET = 124
        private const val SIZE_LENGTH = 12
        private const val TYPE_OFFSET = 156
        private const val PREFIX_OFFSET = 345
        private const val PREFIX_LENGTH = 155
        private const val OCTAL = 8

        fun extract(tar: InputStream, entry: String, output: OutputStream): Boolean {
            val header = ByteArray(BLOCK)
            var found = false
            while (!found && readHeader(tar, header)) {
                val prefix = text(header, PREFIX_OFFSET, PREFIX_LENGTH)
                val name = text(header, 0, NAME_LENGTH).let { if (prefix.isEmpty()) it else "$prefix/$it" }
                val declared = text(header, SIZE_OFFSET, SIZE_LENGTH).trim().toLongOrNull(OCTAL)
                val size = declared?.takeIf { it <= MAX_EXECUTABLE_SIZE } ?: throw IOException("Bad tar entry $name")
                val regular = header[TYPE_OFFSET] == '0'.code.toByte() || header[TYPE_OFFSET] == 0.toByte()
                found = regular && name == entry
                if (found) {
                    output.write(tar.readNBytes(size.toInt()))
                } else {
                    tar.skipNBytes((size + BLOCK - 1) / BLOCK * BLOCK)
                }
            }
            return found
        }

        // Reads the next header; false at the end of the archive (a short read or a block of zeros).
        private fun readHeader(tar: InputStream, header: ByteArray): Boolean {
            val complete = tar.readNBytes(header, 0, BLOCK) == BLOCK
            return complete && header.any { it != 0.toByte() }
        }

        private fun text(header: ByteArray, offset: Int, length: Int): String {
            val end = (offset until offset + length).firstOrNull { header[it] == 0.toByte() } ?: offset + length
            return String(header, offset, end - offset, Charsets.US_ASCII)
        }
    }

    companion object {
        private const val MAX_ARCHIVE_SIZE = 64L * 1024 * 1024
        private const val MAX_EXECUTABLE_SIZE = 64L * 1024 * 1024
        private const val VERSION_CHECK_SECONDS = 10L
        private const val MAX_VERSION_OUTPUT = 4096
        private const val HTTP_OK = 200
        private val CONNECT_TIMEOUT = Duration.ofSeconds(15)
        private val DOWNLOAD_TIMEOUT = Duration.ofMinutes(2)

        /** Whether [path] is a ripgrep executable that starts here (not some other program, not a noexec mount). */
        fun runs(path: Path): Boolean =
            Files.isRegularFile(path) &&
                runCatching {
                    val process = ProcessBuilder(path.toString(), "--version").redirectErrorStream(true).start()
                    process.outputStream.close()
                    // Waits first (the version fits in the pipe buffer): a program that never ends cannot block us.
                    val exited = process.waitFor(VERSION_CHECK_SECONDS, TimeUnit.SECONDS)
                    if (!exited) process.destroyForcibly()
                    exited &&
                        process.exitValue() == 0 &&
                        String(process.inputStream.use { it.readNBytes(MAX_VERSION_OUTPUT) }).startsWith("ripgrep ")
                }.getOrDefault(false)

        /** SHA-256 of a file, in lowercase hexadecimal. */
        fun sha256(path: Path): String =
            Files.newInputStream(path).use { copyHashing(it, OutputStream.nullOutputStream(), Long.MAX_VALUE) }

        // Copies at most `limit` bytes (more is an error) and returns their SHA-256.
        private fun copyHashing(input: InputStream, output: OutputStream, limit: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashing = DigestInputStream(input, digest)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = hashing.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw IOException("Unexpectedly large download")
                output.write(buffer, 0, read)
            }
            return HexFormat.of().formatHex(digest.digest())
        }

        private inline fun ensure(condition: Boolean, message: () -> String) {
            if (!condition) throw IOException(message())
        }

        private fun httpGet(url: String): InputStream {
            val client =
                HttpClient
                    .newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build()
            val request =
                HttpRequest
                    .newBuilder(URI.create(url))
                    .timeout(DOWNLOAD_TIMEOUT)
                    .GET()
                    .build()
            val response =
                try {
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Download interrupted", e)
                }
            if (response.statusCode() != HTTP_OK) {
                response.body().close()
                throw IOException("HTTP ${response.statusCode()} for $url")
            }
            return response.body()
        }
    }
}
