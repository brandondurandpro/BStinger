package org.example.kotlineditor.server.ripgrep

import java.util.Locale

/**
 * The ripgrep release the server runs, pinned like detekt in `./lint`: one version, and the SHA-256 of each
 * official archive (from the `.sha256` files published with the release, checked against the downloaded archives)
 * and of the `rg` binary it contains. A copy that does not match is never run.
 *
 * Upgrading means changing [VERSION] and every checksum below together.
 */
internal object RipgrepRelease {
    /** Pinned ripgrep version: 15.2.0 is the first release with a static (musl) Linux aarch64 build. */
    const val VERSION = "15.2.0"

    private const val DOWNLOADS = "https://github.com/BurntSushi/ripgrep/releases/download"

    /** Address of the official archive for [platform]. */
    fun archiveUrl(platform: RipgrepPlatform): String = "$DOWNLOADS/$VERSION/${platform.archiveName}"
}

/** The official ripgrep builds the server knows how to use, with their pinned checksums. */
internal enum class RipgrepPlatform(
    /** Rust target triple, as it appears in the archive name. */
    val target: String,
    /** SHA-256 of the release archive. */
    val archiveSha256: String,
    /** SHA-256 of the `rg` executable inside the archive. */
    val binarySha256: String,
) {
    LinuxX64(
        "x86_64-unknown-linux-musl",
        "33e15bcf1624b25cdd2a55813a47a2f95dbe126268203e76aa6a585d1e7b149c",
        "e62198eb19b136b88c330af83647b5a962cb99b6b1f066758568f12de1974849",
    ),
    LinuxArm64(
        "aarch64-unknown-linux-musl",
        "800b1e7206afe799dfb5a6901f23147cfaabe0e52210538100f61e86e1740915",
        "c14cdb389f34e504d69e386cfc67d5c5d9a730a990de03ca6910b2a15e30386a",
    ),
    MacX64(
        "x86_64-apple-darwin",
        "af7825fcc69a2afc7a7aea55fc9af90e26421d8f20fe59df32e233c0b8a231c1",
        "0c9a0066db0d26b640777db88045b0ccdd58509a746700e43e1c4ff8707a5ed0",
    ),
    MacArm64(
        "aarch64-apple-darwin",
        "3750b2e93f37e0c692657da574d7019a101c0084da05a790c83fd335bad973e4",
        "a326a1fb48074202e9ad41e4cd1e389eeea372c8c6f7d7e80da81176d5d9430e",
    ),
    WindowsX64(
        "x86_64-pc-windows-msvc",
        "71b2fef860abe467217a538ff31de02f5258807c0129f771846f87bd029aafc5",
        "14231169855ec5205cf5a1b6f1db358ff4aed4247c86b69ce8aae647c77f6680",
    ),
    ;

    private val isWindows: Boolean get() = this == WindowsX64

    /** File name of the release archive (a zip on Windows, a gzipped tar elsewhere). */
    val archiveName: String
        get() = "ripgrep-${RipgrepRelease.VERSION}-$target." + if (isWindows) "zip" else "tar.gz"

    /** Name of the executable, in the archive and on disk. */
    val executableName: String get() = if (isWindows) "rg.exe" else "rg"

    /** Path of the executable inside the archive. */
    val archiveEntry: String get() = "ripgrep-${RipgrepRelease.VERSION}-$target/$executableName"

    /** Platform detection. */
    companion object {
        /** The build for this JVM's system, or null when ripgrep publishes none we pinned. */
        fun current(
            osName: String = System.getProperty("os.name"),
            osArch: String = System.getProperty("os.arch"),
        ): RipgrepPlatform? {
            val os = osName.lowercase(Locale.ROOT)
            val arm = osArch.lowercase(Locale.ROOT) in setOf("aarch64", "arm64")
            val x64 = osArch.lowercase(Locale.ROOT) in setOf("amd64", "x86_64")
            return when {
                os.startsWith("linux") && x64 -> LinuxX64
                os.startsWith("linux") && arm -> LinuxArm64
                os.startsWith("mac") && x64 -> MacX64
                os.startsWith("mac") && arm -> MacArm64
                os.startsWith("windows") && x64 -> WindowsX64
                else -> null
            }
        }
    }
}
