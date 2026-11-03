package org.example.kotlineditor.acp

import org.example.kotlineditor.lsp.provision.Digest
import org.example.kotlineditor.lsp.provision.PinnedTool
import org.example.kotlineditor.lsp.provision.RuntimeDetector
import org.example.kotlineditor.lsp.provision.RuntimeKind
import org.example.kotlineditor.lsp.provision.RuntimeStatus
import org.example.kotlineditor.lsp.provision.TarArchive
import org.example.kotlineditor.lsp.provision.ToolDownloader
import org.example.kotlineditor.model.AgentCommand
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

// The oldest Node.js the npx agents run on.
private const val NODE_MINIMUM = "18"
private const val EXTRACTED = ".extracted"

/**
 * Installs the agents of the ACP registry on the server and says how to start them: the archive of the platform
 * (downloaded once, verified against the registry's SHA-256, extracted), else the npx package on the Node.js of the
 * machine, else the uvx package. An archive without a checksum is never run: its agent needs a command line.
 * Blocking: call it on an IO thread.
 */
internal class AgentInstaller(
    private val downloader: ToolDownloader,
    private val runtimes: RuntimeDetector = RuntimeDetector(),
    private val platform: String? = registryPlatform(),
    private val windows: Boolean = System.getProperty("os.name").startsWith("Windows"),
) {
    private val detected = ConcurrentHashMap<RuntimeKind, RuntimeStatus>()

    /** Why [agent] cannot run on this machine; null when it can. */
    fun unavailable(agent: RegistryAgent): String? {
        val binary = binaryOf(agent)
        val runnable = binary?.sha256 != null || runtimeFor(agent) != null
        return when {
            runnable -> null
            binary != null -> "The registry gives no checksum for its download: set its command line instead"
            agent.npx != null -> "It needs Node.js $NODE_MINIMUM or newer, not found on the server"
            agent.uvx != null -> "It needs uv (uvx), not found on the server"
            else -> "No build of it for ${platform ?: "this platform"}"
        }
    }

    /** The command that starts [agent], installed first when it is an archive; fails with why it cannot. */
    fun command(agent: RegistryAgent): AgentCommand {
        unavailable(agent)?.let { throw IOException("Cannot run ${agent.name}: $it.") }
        val binary = binaryOf(agent)?.takeIf { it.sha256 != null }
        if (binary != null) return installed(agent, binary)
        val npx = agent.npx
        val runtime = checkNotNull(runtimeFor(agent))
        val directory = runtime.path.parent
        return if (npx != null) {
            val program = directory.resolve(if (windows) "npx.cmd" else "npx").toString()
            // npx and the package start `node` from the PATH: the one found first.
            val path = directory.toString() + File.pathSeparator + System.getenv("PATH").orEmpty()
            AgentCommand(listOf(program, "-y", npx.name) + npx.arguments, npx.environment + ("PATH" to path))
        } else {
            val uvx = checkNotNull(agent.uvx)
            AgentCommand(listOf(runtime.path.toString(), uvx.name) + uvx.arguments, uvx.environment)
        }
    }

    private fun binaryOf(agent: RegistryAgent): RegistryBinary? = platform?.let(agent.binaries::get)

    // The runtime of the package of [agent]: Node.js for npx, uvx; null when it is missing.
    private fun runtimeFor(agent: RegistryAgent): RuntimeStatus.Found? {
        val kind =
            when {
                agent.npx != null -> RuntimeKind.Node
                agent.uvx != null -> RuntimeKind.Uvx
                else -> return null
            }
        val minimum = NODE_MINIMUM.takeIf { kind == RuntimeKind.Node }
        val status = detected.computeIfAbsent(kind) { runtimes.detect(it, minimum) }
        return status as? RuntimeStatus.Found
    }

    // Downloads, verifies and extracts the archive once; its program is made executable.
    private fun installed(agent: RegistryAgent, binary: RegistryBinary): AgentCommand {
        val name = binary.archive.substringAfterLast('/').substringBefore('?')
        val digest = Digest(Digest.SHA_256, binary.sha256.orEmpty())
        val tool = PinnedTool("agent-${agent.id}", agent.version, binary.archive, digest, name)
        val archive = downloader.obtain(tool)
        val files = downloader.directoryOf(tool).resolve("files")
        val marker = downloader.directoryOf(tool).resolve(EXTRACTED)
        val done = runCatching { Files.readString(marker) }.getOrNull() == binary.sha256
        if (!done || !Files.isDirectory(files)) {
            extract(archive, files, binary.command)
            Files.writeString(marker, binary.sha256.orEmpty())
        }
        val program = files.resolve(binary.command.replace('\\', '/')).normalize()
        if (!program.startsWith(files) || !Files.isRegularFile(program)) {
            throw IOException("The archive of ${agent.name} has no ${binary.command}")
        }
        program.toFile().setExecutable(true)
        return AgentCommand(listOf(program.toString()) + binary.arguments, binary.environment)
    }
}

/** Extracts [archive] (`.zip`, `.tar.gz`, `.tgz`, or the program itself) into [into], emptied first. */
internal fun extract(archive: Path, into: Path, command: String) {
    into.toFile().deleteRecursively()
    Files.createDirectories(into)
    val name = archive.fileName.toString().lowercase()
    when {
        name.endsWith(".zip") -> {
            Files.newInputStream(archive).use { unzip(ZipInputStream(it), into) }
        }

        name.endsWith(".tar.gz") || name.endsWith(".tgz") -> {
            Files.newInputStream(archive).use { TarArchive.extract(GZIPInputStream(it), into) }
        }

        name.endsWith(".tar.bz2") || name.endsWith(".tbz2") -> {
            throw IOException("bzip2 archives are not supported")
        }

        else -> {
            Files.copy(archive, into.resolve(command.replace('\\', '/')).normalize())
        }
    }
}

// Every file of the zip, none outside [into].
private fun unzip(zip: ZipInputStream, into: Path) {
    val root = into.toAbsolutePath().normalize()
    while (true) {
        val entry = zip.nextEntry ?: break
        val target = root.resolve(entry.name.replace('\\', '/')).normalize()
        if (!target.startsWith(root)) throw IOException("The archive places ${entry.name} outside its directory")
        if (entry.isDirectory) {
            Files.createDirectories(target)
        } else {
            Files.createDirectories(target.parent)
            Files.newOutputStream(target).use { zip.copyTo(it) }
        }
    }
}
