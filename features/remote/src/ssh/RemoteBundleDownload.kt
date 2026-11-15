package org.example.kotlineditor.remote

import java.io.File
import java.io.InterruptedIOException

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
        val directory = downloadDirectory(version)
        val request = DownloadRequest(releaseUrl(version), name, directory, ::missingMessage)
        val alreadyKept = File(directory, name).isFile
        if (!alreadyKept) onProgress("Downloading the server $version for ${target.id}")
        return download(request)
    }

    // The failures of the transfer are the remote development's own.
    private fun download(request: DownloadRequest): File =
        try {
            VerifiedDownload().fetch(request)
        } catch (e: InterruptedIOException) {
            throw RemoteException("Download cancelled.", e)
        } catch (e: DownloadException) {
            throw RemoteException(e.message.orEmpty(), e)
        }

    private fun missingMessage(url: String): String =
        "$url does not exist: that release has no server bundle. Choose \"Local file\" in Settings > Remote Development."
}
