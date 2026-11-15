package org.example.kotlineditor.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.example.kotlineditor.remote.DownloadRequest
import org.example.kotlineditor.remote.RemoteBundleDownload
import org.example.kotlineditor.remote.VerifiedDownload
import java.io.File

/**
 * The files of the updates on disk: the verified download of each release in its own folder of [directory], named
 * after the version. The URL of a file is built from the validated tag of the release, never taken from the answer
 * of the GitHub API.
 */
class UpdateDownloads(
    private val directory: File,
    private val baseUrl: () -> String,
    private val downloader: VerifiedDownload,
) {
    /** The folder of the files of [release]. */
    fun directoryOf(release: AvailableRelease): File = File(directory, release.version.toString())

    /**
     * Downloads [asset] of [release] (a verified file already there is reused). Blocking, but interrupted when the
     * coroutine is cancelled: the transfer stops and its `.part` file is deleted.
     */
    suspend fun fetch(
        release: AvailableRelease,
        asset: ReleaseAsset,
        onProgress: (done: Long, total: Long) -> Unit,
    ): File {
        val url = "${baseUrl()}/${release.tag}"
        val request = DownloadRequest(url, asset.name, directoryOf(release), ::missingMessage)
        return runInterruptible(Dispatchers.IO) { downloader.fetch(request, onProgress) }
    }

    /** A new download deletes what other versions, and a crashed installer, left; the log of the last install stays. */
    fun deleteOtherVersions(release: AvailableRelease) {
        val current = directoryOf(release)
        val leftovers = directory.listFiles { file -> file != current && file.name != UpdateSwap.LOG_NAME }
        leftovers?.forEach(UpdateStaging::deleteTree)
    }

    private fun missingMessage(url: String): String =
        "The release has no file at $url. It may have been withdrawn: open the release page to check."

    companion object {
        /** `-D<name>=<base url>` replaces `https://github.com/<repository>/releases/download` (tests, mirrors). */
        const val BASE_PROPERTY = "kotlin.editor.update.downloads"

        fun defaultBase(): String =
            System.getProperty(BASE_PROPERTY)?.trimEnd('/')
                ?: "https://github.com/${RemoteBundleDownload.REPOSITORY}/releases/download"
    }
}
