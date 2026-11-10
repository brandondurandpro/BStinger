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

/** One npm package of a [PinnedBundle]: its [name] (`typescript`, `@vue/language-server`), tarball and checksum. */
data class PinnedPackage(
    /** Name of the package. */
    val name: String,
    /** Address of its `.tgz`, exactly the one verified in the pin's source. */
    val url: String,
    /** Its `dist.integrity` (SHA-512) or another checksum the tarball must have. */
    val digest: Digest,
    /**
     * Where it installs under `node_modules`: its [name], or a path inside another package's own `node_modules` when
     * two versions of it are needed (`@vue/language-plugin-pug/node_modules/@volar/source-map`).
     */
    val location: String = name,
)

/**
 * Several npm packages installed side by side in one `node_modules`, the way `npm install` lays them out: a language
 * server and the `typescript` it finds next to it. Each tarball is pinned and verified like a [PinnedTool]; nothing
 * runs at install time (no scripts, no resolution of dependencies: a package needing one is pinned here too).
 */
data class PinnedBundle(
    /** Name the bundle installs under: `<toolsDirectory>/<name>/<version>/`. */
    val name: String,
    /** Version of the bundle (that of its main package), also the directory it installs into. */
    val version: String,
    /** The packages, installed under `node_modules/<package location>/`. */
    val packages: List<PinnedPackage>,
)
