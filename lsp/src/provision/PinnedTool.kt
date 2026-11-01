package org.example.kotlineditor.lsp.provision

/**
 * A third-party artifact pinned to one origin and one content: one address ([url]), one expected checksum
 * ([digest]), one version ([version]). A file that does not verify is never installed and never run; a copy
 * that verified once is reused instead of downloaded again.
 */
data class PinnedTool(
    /** Name the tool installs under: `<toolsDirectory>/<name>/<version>/`. */
    val name: String,
    /** Version published upstream, also the directory the copy installs into. */
    val version: String,
    /** Address the artifact is fetched from, exactly the one verified in the pin's source. */
    val url: String,
    /** Checksum the downloaded content must have. */
    val digest: Digest,
    /** File name the downloaded artifact is stored under, inside the version directory. */
    val fileName: String,
    /** How the artifact is installed, or just stored. */
    val unpack: Unpack = Unpack.None,
)

/** How a pinned artifact (typically an npm tarball) is turned into the files that actually run. */
enum class Unpack {
    /** A single file (like a `phpactor.phar`) kept as it is downloaded and verified. */
    None,

    /**
     * An npm `.tgz`: a gzip-wrapped tar whose entries sit under `package/`. It is extracted into
     * `<tools>/<name>/<version>/package/` once the whole archive verified against the pin.
     */
    NpmTarball,
}
