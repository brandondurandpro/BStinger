package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.example.kotlineditor.remote.RemoteBundleDownload
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A file attached to a release: only its name and size are used, never a URL of the API's answer. */
data class ReleaseAsset(val name: String, val size: Long)

/** A release of the repository, as the GitHub API lists it. */
data class ReleaseInfo(val tag: String, val draft: Boolean, val assets: List<ReleaseAsset> = emptyList())

/**
 * A published release newer than the IDE: its [tag] (`v1.2.3`, checked to be `v` and a [version]), the [version] and
 * the [assets] it carries.
 */
data class AvailableRelease(val tag: String, val version: AppVersion, val assets: List<ReleaseAsset>) {
    /** The page of the release on GitHub: its notes and its downloads. */
    val pageUrl: String get() = "https://github.com/${RemoteBundleDownload.REPOSITORY}/releases/tag/$tag"
}

/** Where the releases come from: the GitHub API, or a stand-in (tests). Throws when they cannot be read. */
fun interface ReleaseSource {
    suspend fun releases(): List<ReleaseInfo>
}

/** The published releases of the repository of the IDE, from `api.github.com`. */
class GitHubReleases(
    private val repository: String = RemoteBundleDownload.REPOSITORY,
    private val endpoint: String = System.getProperty(ENDPOINT_PROPERTY) ?: "https://api.github.com",
) : ReleaseSource {
    override suspend fun releases(): List<ReleaseInfo> =
        withContext(Dispatchers.IO) {
            val request =
                HttpRequest
                    .newBuilder(URI("${endpoint.trimEnd('/')}/repos/$repository/releases?per_page=$PAGE_SIZE"))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "kotlin-editor-update-check")
                    .GET()
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != HTTP_OK) throw IOException("GitHub answered ${response.statusCode()}")
            parseReleases(response.body())
        }

    companion object {
        /** `-D<name>=<base url>` replaces `https://api.github.com` (tests, mirrors). */
        const val ENDPOINT_PROPERTY = "kotlin.editor.update.api"
        private const val PAGE_SIZE = 30
        private const val HTTP_OK = 200
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val REQUEST_TIMEOUT_SECONDS = 20L

        private val client: HttpClient =
            HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build()

        /** The releases listed by the JSON [body]; throws when it is not the list of releases. */
        fun parseReleases(body: String): List<ReleaseInfo> {
            val list = Json.parseToJsonElement(body) as? JsonArray ?: throw IOException("Not a list of releases")
            return list.mapNotNull { element ->
                val release = element as? JsonObject
                val tag = release?.get("tag_name")?.jsonPrimitive?.content
                val draft = release?.get("draft")?.jsonPrimitive?.booleanOrNull ?: false
                tag?.let { ReleaseInfo(it, draft, assetsOf(release)) }
            }
        }

        // A release without `assets`, or an asset without a name or a size, lists what can be read.
        private fun assetsOf(release: JsonObject): List<ReleaseAsset> {
            val assets = release["assets"] as? JsonArray ?: return emptyList()
            return assets.mapNotNull { element ->
                val asset = element as? JsonObject
                val name = asset?.get("name")?.jsonPrimitive?.content
                val size = asset?.get("size")?.jsonPrimitive?.longOrNull
                if (name == null || size == null) null else ReleaseAsset(name, size)
            }
        }
    }
}

/**
 * Tells whether a release newer than the IDE is published. One per process, shared by the windows: [run] checks
 * once, then every [interval]. Nothing is reported when the version of the IDE is unknown (a development run), and
 * every failure (offline, rate limit, no release yet) is silent: the next check tries again.
 */
@Stable
class UpdateNotifier(
    private val currentVersion: String?,
    private val source: ReleaseSource,
    private val interval: Duration = Duration.ofHours(CHECK_HOURS),
    private val openUrl: (String) -> Unit = ::openInBrowser,
    /** Downloads and installs the release found; one per process like the notifier. */
    val updater: AppUpdater = AppUpdater(),
) {
    /** The version of this IDE, null when it is unknown (a development run). */
    val running: AppVersion? = currentVersion?.let(AppVersion::parse)

    /** The newest release when it is newer than this IDE, else null. */
    var available: AvailableRelease? by mutableStateOf(null)
        private set

    /** Opens the page of [release] in the browser: its notes and its downloads. */
    fun openReleasePage(release: AvailableRelease) {
        openUrl(release.pageUrl)
    }

    /** Checks now and every [interval], until the calling coroutine is cancelled. */
    suspend fun run() {
        val own = running ?: return
        while (true) {
            checkOnce(own)
            delay(interval.toMillis())
        }
    }

    private suspend fun checkOnce(own: AppVersion) {
        val releases =
            try {
                source.releases()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expected: Exception) {
                System.err.println("Update check failed: ${expected.message}")
                return
            }
        available = newestAfter(own, releases)
    }

    companion object {
        private const val CHECK_HOURS = 24L

        /**
         * The newest published release newer than [own]. A pre-release (`-rc1`) is only offered to a user of a
         * pre-release; a tag that is not strictly `v<numbers>[-suffix]` is ignored.
         */
        fun newestAfter(own: AppVersion, releases: List<ReleaseInfo>): AvailableRelease? {
            val candidates =
                releases
                    .filterNot { it.draft }
                    .filter { it.tag.startsWith("v") }
                    .mapNotNull(::availableOf)
                    .filter { own.isPreRelease || !it.version.isPreRelease }
            val newest = candidates.maxByOrNull { it.version } ?: return null
            return newest.takeIf { it.version > own }
        }

        private fun availableOf(release: ReleaseInfo): AvailableRelease? {
            val version = AppVersion.parse(release.tag) ?: return null
            return AvailableRelease(release.tag, version, release.assets)
        }
    }
}
