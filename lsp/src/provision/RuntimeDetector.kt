package org.example.kotlineditor.lsp.provision

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/** A program a language server runs on, such as PHP for phpactor and Node.js for intelephense. */
enum class RuntimeKind(
    /** Name shown to the user. */
    val displayName: String,
    /** Executable name looked up on the `PATH`. */
    val executable: String,
    /** Arguments that make it print its version, and nothing else, on stdout. */
    val versionArguments: List<String>,
    /** Names of versioned executables installed side by side (`php8.4`), null when there are none. */
    val versionedNames: Regex? = null,
) {
    /** The PHP command line interpreter; Debian and Ubuntu packages install `php8.4` next to `php`. */
    Php("PHP", "php", listOf("-r", "echo PHP_VERSION;"), Regex("""php\d+(\.\d+)?""")),

    /** Node.js. */
    Node("Node.js", "node", listOf("--version")),
}

/** What [RuntimeDetector] found for a [RuntimeKind]. */
sealed interface RuntimeStatus {
    /** The runtime is there and recent enough. */
    data class Found(
        /** The runtime. */
        val kind: RuntimeKind,
        /** Its executable. */
        val path: Path,
        /** Its version, such as `8.3.6`. */
        val version: String,
    ) : RuntimeStatus

    /** No usable executable: none on the `PATH`, or the configured one does not run. */
    data class Missing(
        /** The runtime. */
        val kind: RuntimeKind,
        /** Why, for the user. */
        val reason: String,
    ) : RuntimeStatus

    /** Found, but older than the language server needs. */
    data class TooOld(
        /** The runtime. */
        val kind: RuntimeKind,
        /** Its executable. */
        val path: Path,
        /** Its version. */
        val version: String,
        /** The oldest version the language server works with. */
        val minimum: String,
    ) : RuntimeStatus
}

/**
 * Finds the runtimes language servers need on the server machine: the configured path when there is one (a
 * setting), otherwise every candidate of the `PATH` (with `PATHEXT` on Windows), versioned names included (`php8.4`
 * next to an old `php`), then of [extraDirectories] (version managers such as nvm, which a server started outside a
 * login shell does not have on its `PATH`). Each is asked its version with a short timeout, and the newest wins.
 */
class RuntimeDetector(
    // Paths set by the user, by runtime; they win over the `PATH`.
    private val configured: Map<RuntimeKind, String> = emptyMap(),
    private val pathEnvironment: String = System.getenv("PATH").orEmpty(),
    private val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows"),
    private val pathExtensions: String = System.getenv("PATHEXT") ?: ".COM;.EXE;.BAT;.CMD",
    // Directories searched after the PATH, by runtime.
    private val extraDirectories: (RuntimeKind) -> List<Path> = ::versionManagerDirectories,
    // Runs an executable with arguments and returns its stdout, or null when it fails; replaceable in tests.
    private val probe: (Path, List<String>) -> String? = ::runForOutput,
) {
    /** The [kind] runtime, checked against the [minimum] version (`"8.2"`), or any version when null. */
    fun detect(kind: RuntimeKind, minimum: String? = null): RuntimeStatus {
        val explicit = configured[kind]
        val candidates = if (explicit != null) listOf(Paths.get(explicit)) else candidatesOf(kind)
        val found =
            candidates
                .mapNotNull { path -> probe(path, kind.versionArguments)?.let(::parseVersion)?.let { path to it } }
                // The newest; the first found among equals, so the PATH order still decides ties.
                .fold(null as Pair<Path, String>?) { best, next ->
                    if (best == null || compareVersions(next.second, best.second) > 0) next else best
                }
        return when {
            found == null && explicit != null -> {
                RuntimeStatus.Missing(
                    kind,
                    "${kind.displayName} at $explicit does not run",
                )
            }

            found == null -> {
                RuntimeStatus.Missing(kind, "${kind.displayName} not found")
            }

            minimum != null && compareVersions(found.second, minimum) < 0 -> {
                RuntimeStatus.TooOld(kind, found.first, found.second, minimum)
            }

            else -> {
                RuntimeStatus.Found(kind, found.first, found.second)
            }
        }
    }

    private fun candidatesOf(kind: RuntimeKind): List<Path> {
        val extensions = if (isWindows) pathExtensions.split(';').filter { it.isNotBlank() } else listOf("")
        val names = extensions.map { kind.executable + it.lowercase() }
        val onPath =
            pathEnvironment
                .split(File.pathSeparatorChar)
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { Paths.get(it) }.getOrNull() }
        return (onPath + extraDirectories(kind))
            .flatMap { directory -> names.map(directory::resolve) + versioned(directory, kind, extensions) }
            .filter { Files.isRegularFile(it) && (isWindows || Files.isExecutable(it)) }
            .distinctBy { runCatching { it.toRealPath() }.getOrDefault(it) }
    }

    private fun versioned(directory: Path, kind: RuntimeKind, extensions: List<String>): List<Path> {
        val pattern = kind.versionedNames
        if (pattern == null || !Files.isDirectory(directory)) return emptyList()
        return runCatching {
            Files.list(directory).use { entries ->
                entries
                    .filter { isVersioned(it, pattern, extensions) }
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun isVersioned(entry: Path, pattern: Regex, extensions: List<String>): Boolean {
        val name = entry.fileName.toString().lowercase()
        return extensions.any { extension ->
            val suffix = extension.lowercase()
            name.endsWith(suffix) && pattern.matches(name.removeSuffix(suffix))
        }
    }

    /** Version helpers. */
    companion object {
        private const val TIMEOUT_SECONDS = 5L
        private const val MAX_OUTPUT = 4096

        /** `8.3.6` from `8.3.6`, `v20.11.1` or `8.3.6-1ubuntu`; null when the output holds no version. */
        fun parseVersion(output: String): String? = Regex("""\d+(\.\d+)+""").find(output)?.value

        /** Numeric comparison of dotted versions: `8.10` is newer than `8.9`. */
        fun compareVersions(left: String, right: String): Int {
            val a = left.split('.').map { it.toIntOrNull() ?: 0 }
            val b = right.split('.').map { it.toIntOrNull() ?: 0 }
            val differences =
                (
                    0 until
                        maxOf(
                            a.size,
                            b.size,
                        )
                    ).map { (a.getOrNull(it) ?: 0).compareTo(b.getOrNull(it) ?: 0) }
            return differences.firstOrNull { it != 0 } ?: 0
        }

        // Where version managers install runtimes: nvm, fnm, volta and asdf for Node.js.
        private fun versionManagerDirectories(kind: RuntimeKind): List<Path> {
            if (kind != RuntimeKind.Node) return emptyList()
            val home = Paths.get(System.getProperty("user.home"))
            val nvm = listOf(home.resolve(".nvm/versions/node"), home.resolve(".local/share/fnm/node-versions"))
            val installed =
                nvm.flatMap { root ->
                    runCatching { Files.list(root).use { it.toList() } }.getOrDefault(emptyList()).flatMap {
                        listOf(it.resolve("bin"), it.resolve("installation/bin"))
                    }
                }
            val managers = listOf(home.resolve(".volta/bin"), home.resolve(".asdf/shims"), Paths.get("/usr/local/bin"))
            return installed + managers
        }

        // Waits first (a version fits in the pipe buffer), so a program that never ends cannot block the server.
        private fun runForOutput(path: Path, arguments: List<String>): String? =
            runCatching {
                val process = ProcessBuilder(listOf(path.toString()) + arguments).redirectErrorStream(true).start()
                process.outputStream.close()
                val exited = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!exited) process.destroyForcibly()
                val output = process.inputStream.use { String(it.readNBytes(MAX_OUTPUT)) }
                output.takeIf { exited && process.exitValue() == 0 }
            }.getOrNull()
    }
}
