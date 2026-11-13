package org.example.kotlineditor.remote

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

/**
 * Downloads the server bundle of the GitHub release of this IDE's version, and keeps it in
 * `~/.kotlin-editor/bundles/downloads/<version>/`: a bundle is fetched once, checked against the release's
 * `SHA256SUMS.txt`, and only a verified file is ever kept.
 *
 * The release is `v<version>` of the repository; `-Dkotlin.editor.remote.releases=<base url>` replaces
 * `https://github.com/<repository>/releases/download/v<version>` (tests, mirrors).
 */
object RemoteBundleDownload {
    const val REPOSITORY = "brandondurandpro/BStinger"
    const val VERSION_PROPERTY = "kotlin.editor.version"
    const val RELEASES_PROPERTY = "kotlin.editor.remote.releases"
    private const val SUMS_NAME = "SHA256SUMS.txt"
    private const val BUFFER_SIZE = 64 * 1024
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val REQUEST_TIMEOUT_MINUTES = 10L
    private const val HTTP_OK = 200
    private const val HTTP_NOT_FOUND = 404

    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build()

    /** Name of the asset of [target] in the release of [version]. */
    fun assetName(version: String, target: RemoteTarget): String = "kotlin-editor-remote-$version-${target.id}.tar.gz"

    /** The version of this IDE, null when this build does not know it (a development run). */
    fun ideVersion(): String? = System.getProperty(VERSION_PROPERTY)?.takeIf { it.isNotBlank() }

    /** The folder of the bundles downloaded for [version], or for every version when it is null. */
    fun downloadDirectory(version: String? = null): File {
        val downloads = File(System.getProperty("user.home"), ".kotlin-editor/bundles/downloads")
        return if (version == null) downloads else File(downloads, version)
    }

    private fun releaseUrl(version: String): String =
        System.getProperty(RELEASES_PROPERTY)?.trimEnd('/')
            ?: "https://github.com/$REPOSITORY/releases/download/v$version"

    /** The verified bundle of [target], downloaded if it is not kept yet. */
    fun fetch(target: RemoteTarget, onProgress: (String) -> Unit = {}): File {
        val version =
            ideVersion()
                ?: throw RemoteException(
                    "This build of the IDE does not know its version, so it cannot pick a release to download. " +
                        "Choose \"Local file\" in Settings > Remote Development.",
                )
        val name = assetName(version, target)
        val kept = File(downloadDirectory(version), name)
        if (kept.isFile) return kept
        onProgress("Downloading the server $version for ${target.id}")
        val base = releaseUrl(version)
        val expected = expectedHash(base, name)
        val directory = kept.parentFile
        Files.createDirectories(directory.toPath())
        val partial = Files.createTempFile(directory.toPath(), name, ".part")
        try {
            val actual = download("$base/$name", partial.toFile())
            if (actual != expected) {
                throw RemoteException(
                    "The downloaded server $name is corrupted (checksum $actual, expected $expected).",
                )
            }
            Files.move(partial, kept.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(partial)
        }
        return kept
    }

    // The line of SHA256SUMS.txt naming [name]: `<hash>  <name>`.
    private fun expectedHash(base: String, name: String): String {
        val sums = getText("$base/$SUMS_NAME")
        val lines = sums.lines().map { it.trim().split(Regex("\\s+"), limit = 2) }
        val line = lines.firstOrNull { it.size == 2 && it[1].trimStart('*') == name }
        return line?.get(0)?.lowercase()
            ?: throw RemoteException("$SUMS_NAME of the release has no checksum for $name.")
    }

    private fun getText(url: String): String {
        val response = send(url, HttpResponse.BodyHandlers.ofString())
        return response.body()
    }

    // Streams the body to [file] and returns its SHA-256, computed on the way.
    private fun download(url: String, file: File): String {
        val response = send(url, HttpResponse.BodyHandlers.ofInputStream())
        val digest = MessageDigest.getInstance("SHA-256")
        response.body().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("ThrowsCount") // each failure of the transfer says what could not be downloaded
    private fun <T> send(url: String, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(REQUEST_TIMEOUT_MINUTES))
                .GET()
                .build()
        val response =
            try {
                client.send(request, handler)
            } catch (e: IOException) {
                throw RemoteException("Cannot download $url: ${e.message}", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RemoteException("Cannot download $url: interrupted.", e)
            }
        val status = response.statusCode()
        if (status != HTTP_OK) throw RemoteException(statusMessage(url, status))
        return response
    }

    private fun statusMessage(url: String, status: Int): String =
        if (status == HTTP_NOT_FOUND) {
            "$url does not exist: that release has no server bundle. " +
                "Choose \"Local file\" in Settings > Remote Development."
        } else {
            "Cannot download $url: HTTP $status."
        }
}
