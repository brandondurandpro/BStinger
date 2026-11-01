package org.example.kotlineditor.lsp.provision

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
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * Installs pinned third-party tools (language servers) into `<toolsDirectory>/<name>/<version>/`, the way ripgrep
 * is installed: downloaded to a temporary file, verified against the pinned checksum while it streams, then moved
 * in place atomically. A copy verified earlier is reused without downloading again; a file that does not verify is
 * never installed. Blocking: call it on an IO thread.
 */
class ToolDownloader(
    /** Root of the tools downloaded on first use, shared with ripgrep. */
    val toolsDirectory: Path = Paths.get(System.getProperty("user.home"), ".kotlin-editor", "tools"),
    private val fetch: (String) -> InputStream = ::httpGet,
) {
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * The installed [tool]: the downloaded file itself for [Unpack.None], the extracted `package/` directory for
     * [Unpack.NpmTarball]. Concurrent calls for the same tool download it once.
     *
     * @throws IOException when the download fails or does not match the pinned checksum; nothing is installed then.
     */
    fun obtain(tool: PinnedTool): Path {
        val directory = directoryOf(tool)
        return synchronized(locks.computeIfAbsent("${tool.name}/${tool.version}") { Any() }) {
            when (tool.unpack) {
                Unpack.None -> obtainFile(tool, directory)
                Unpack.NpmTarball -> obtainPackage(tool, directory)
            }
        }
    }

    /** Where [tool] is (or would be) installed. */
    fun directoryOf(tool: PinnedTool): Path = toolsDirectory.resolve(tool.name).resolve(tool.version)

    private fun obtainFile(tool: PinnedTool, directory: Path): Path {
        val target = directory.resolve(tool.fileName)
        if (!isVerified(target, tool.digest)) {
            val download = download(tool, directory)
            try {
                moveInPlace(download, target)
            } finally {
                Files.deleteIfExists(download)
            }
        }
        return target
    }

    // The archive is extracted next to its final place, then renamed: a half-extracted package is never used.
    // The marker, written last, records which pinned archive the package came from.
    private fun obtainPackage(tool: PinnedTool, directory: Path): Path {
        val target = directory.resolve(PACKAGE)
        val marker = directory.resolve(INSTALLED_MARKER)
        val recorded = runCatching { Files.readString(marker) }.getOrNull()
        if (!Files.isDirectory(target) || recorded != tool.digest.value) {
            val archive = download(tool, directory)
            val staging = Files.createTempDirectory(directory, "extract-")
            try {
                Files.newInputStream(archive).use { TarArchive.extract(GZIPInputStream(it), staging) }
                val extracted = staging.resolve(PACKAGE)
                if (!Files.isDirectory(extracted)) throw IOException("${tool.fileName} has no $PACKAGE/ directory")
                Files.deleteIfExists(marker)
                deleteTree(target)
                Files.move(extracted, target)
                Files.writeString(marker, tool.digest.value)
            } finally {
                Files.deleteIfExists(archive)
                deleteTree(staging)
            }
        }
        return target
    }

    // Downloads into a temporary file of the version directory, checking size and checksum while it streams.
    private fun download(tool: PinnedTool, directory: Path): Path {
        Files.createDirectories(directory)
        val part = Files.createTempFile(directory, "${tool.fileName}-", ".part")
        try {
            val computed =
                fetch(tool.url).use { input ->
                    Files.newOutputStream(part).use { output -> copyHashing(input, output, tool.digest.algorithm) }
                }
            if (!tool.digest.matches(computed)) {
                throw IOException("Checksum mismatch for ${tool.fileName} ${tool.version}: not the pinned release")
            }
            return part
        } catch (failure: IOException) {
            Files.deleteIfExists(part)
            throw failure
        }
    }

    private fun moveInPlace(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isVerified(path: Path, digest: Digest): Boolean {
        val computed =
            runCatching {
                Files.newInputStream(path).use { copyHashing(it, OutputStream.nullOutputStream(), digest.algorithm) }
            }
        return Files.isRegularFile(path) && computed.getOrNull()?.let(digest::matches) == true
    }

    private companion object {
        const val PACKAGE = "package"
        const val INSTALLED_MARKER = ".installed"
        const val MAX_DOWNLOAD_SIZE = 256L * 1024 * 1024
        const val HTTP_OK = 200
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)
        val DOWNLOAD_TIMEOUT: Duration = Duration.ofMinutes(5)

        // Copies at most MAX_DOWNLOAD_SIZE bytes (more is an error) and returns their checksum.
        fun copyHashing(input: InputStream, output: OutputStream, algorithm: String): ByteArray {
            val digest = MessageDigest.getInstance(algorithm)
            val hashing = DigestInputStream(input, digest)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = hashing.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_DOWNLOAD_SIZE) throw IOException("Unexpectedly large download")
                output.write(buffer, 0, read)
            }
            return digest.digest()
        }

        fun deleteTree(path: Path) {
            if (!Files.exists(path)) return
            Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }

        fun httpGet(url: String): InputStream {
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
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Download interrupted", interrupted)
                }
            if (response.statusCode() != HTTP_OK) {
                response.body().close()
                throw IOException("HTTP ${response.statusCode()} for $url")
            }
            return response.body()
        }
    }
}
