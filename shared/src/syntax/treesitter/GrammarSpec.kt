package org.example.kotlineditor.syntax.treesitter

/**
 * A tree-sitter grammar the editor can download: the jar `io.github.bonede:tree-sitter-<id>:<version>` (prebuilt by
 * bonede/tree-sitter-ng for every platform in [platforms]), checked against [sha256], whose library exports [symbol].
 * Its highlight query is bundled as the resource `/tree-sitter/<id>/highlights.scm`.
 */
class GrammarSpec(
    val id: String,
    /** Shown in the status bar. */
    val name: String,
    val version: String,
    val sha256: String,
    val symbol: String,
    val platforms: Set<String>,
    val extensions: Set<String>,
    val fileNames: Set<String>,
) {
    val artifact: String get() = "tree-sitter-$id"

    /** Path of the native library of [platform] inside the jar. */
    fun libraryEntry(platform: NativePlatform): String = "lib/${platform.id}-$artifact.${platform.libraryExtension}"

    override fun toString(): String = "$artifact:$version"

    companion object {
        private val byFileName: Map<String, GrammarSpec> =
            GRAMMAR_CATALOG.flatMap { spec -> spec.fileNames.map { it to spec } }.toMap()
        private val byExtension: Map<String, GrammarSpec> =
            GRAMMAR_CATALOG.flatMap { spec -> spec.extensions.map { it to spec } }.toMap()

        val all: List<GrammarSpec> get() = GRAMMAR_CATALOG

        /** The grammar of the file named [name] (by its full name, then its extension), `null` if there is none. */
        fun forFileName(name: String): GrammarSpec? =
            byFileName[name] ?: byExtension[name.substringAfterLast('.', "").lowercase()]
    }
}

/** A platform the prebuilt tree-sitter libraries exist for, named like their files (`x86_64-linux-gnu`). */
class NativePlatform(val id: String, val libraryExtension: String) {
    companion object {
        /** The platform of this JVM, `null` when no prebuilt library exists for it (the line lexer is used). */
        val current: NativePlatform? by lazy {
            forSystem(System.getProperty("os.name"), System.getProperty("os.arch"))
        }

        private const val X64 = "x86_64"
        private const val ARM64 = "aarch64"

        /** The platform of `os.name` [osName] and `os.arch` [osArch], `null` if no library is built for it. */
        fun forSystem(osName: String, osArch: String): NativePlatform? {
            val arch =
                when (osArch.lowercase()) {
                    "amd64", X64 -> X64
                    ARM64, "arm64" -> ARM64
                    else -> null
                }
            val os = osName.lowercase()
            return when {
                arch == null -> null
                os.startsWith("linux") -> NativePlatform("$arch-linux-gnu", "so")
                os.startsWith("mac") -> NativePlatform("$arch-macos", "dylib")
                os.startsWith("windows") && arch == X64 -> NativePlatform("$arch-windows", "dll")
                else -> null
            }
        }
    }
}
