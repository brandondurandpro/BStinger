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
import org.example.kotlineditor.remote.RemoteBundleDownload
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A version number such as `1.2.3`, or `1.2.3-rc1`: what the tags `v1.2.3` of the GitHub releases carry. */
class AppVersion private constructor(private val numbers: List<Int>, private val suffix: String?) :
    Comparable<AppVersion> {
    /** Whether this is a pre-release (`1.2.3-rc1`): older than `1.2.3`. */
    val isPreRelease: Boolean get() = suffix != null

    override fun compareTo(other: AppVersion): Int {
        val size = maxOf(numbers.size, other.numbers.size)
        for (index in 0 until size) {
            val mine = numbers.getOrElse(index) { 0 }
            val theirs = other.numbers.getOrElse(index) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }
        val mySuffix = suffix
        val theirSuffix = other.suffix
        return when {
            mySuffix == theirSuffix -> 0
            mySuffix == null -> 1
            theirSuffix == null -> -1
            else -> compareNaturally(mySuffix, theirSuffix)
        }
    }

    override fun equals(other: Any?): Boolean = other is AppVersion && compareTo(other) == 0

    override fun hashCode(): Int = numbers.dropLastWhile { it == 0 }.hashCode() * 31 + (suffix?.hashCode() ?: 0)

    // rc2 < rc10: the digits of a suffix are compared as numbers.
    private fun compareNaturally(first: String, second: String): Int {
        val mine = CHUNK.findAll(first).map { it.value }.toList()
        val theirs = CHUNK.findAll(second).map { it.value }.toList()
        for ((a, b) in mine.zip(theirs)) {
            val numbers = a.toBigIntegerOrNull()?.let { x -> b.toBigIntegerOrNull()?.let { y -> x.compareTo(y) } }
            val order = numbers ?: a.compareTo(b)
            if (order != 0) return order
        }
        return mine.size.compareTo(theirs.size)
    }

    companion object {
        private val CHUNK = Regex("""\d+|\D+""")
        private const val MAX_NUMBERS = 4
        private val PATTERN = Regex("""v?(\d{1,9}(?:\.\d{1,9}){0,${MAX_NUMBERS - 1}})(?:-([0-9A-Za-z.]{1,32}))?""")

        /** The version written in [text], with or without the `v` of a tag; null when it is not one. */
        fun parse(text: String): AppVersion? {
            val match = PATTERN.matchEntire(text) ?: return null
            val numbers = match.groupValues[1].split('.').map { it.toInt() }
            val suffix = match.groups[2]?.value
            return AppVersion(numbers, suffix)
        }
    }
}

/** A release of the repository, as the GitHub API lists it. */
data class ReleaseInfo(val tag: String, val draft: Boolean)

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
                tag?.let { ReleaseInfo(it, draft) }
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
) {
    /** The tag of the newest release when it is newer than this IDE (`v1.2.3`), else null. */
    var available: String? by mutableStateOf(null)
        private set

    /** The page of that release, null when there is none. */
    val releaseUrl: String?
        get() = available?.let { "https://github.com/${RemoteBundleDownload.REPOSITORY}/releases/tag/$it" }

    /** Opens the release page in the browser. */
    fun openReleasePage() {
        releaseUrl?.let(openUrl)
    }

    /** Checks now and every [interval], until the calling coroutine is cancelled. */
    suspend fun run() {
        val own = currentVersion?.let(AppVersion::parse) ?: return
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
         * The tag of the newest published release newer than [own]. A pre-release (`-rc1`) is only offered to a
         * user of a pre-release; a tag that is not strictly `v<numbers>[-suffix]` is ignored.
         */
        fun newestAfter(own: AppVersion, releases: List<ReleaseInfo>): String? {
            val candidates =
                releases
                    .filterNot { it.draft }
                    .filter { it.tag.startsWith("v") }
                    .mapNotNull { release -> AppVersion.parse(release.tag)?.let { release.tag to it } }
                    .filter { (_, version) -> own.isPreRelease || !version.isPreRelease }
            val newest = candidates.maxByOrNull { it.second } ?: return null
            return newest.first.takeIf { newest.second > own }
        }
    }
}
