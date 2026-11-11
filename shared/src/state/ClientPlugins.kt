package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.example.kotlineditor.plugin.core.PluginManifest
import org.example.kotlineditor.plugin.core.UnloadOutcome
import org.example.kotlineditor.plugin.host.LoadResult
import org.example.kotlineditor.plugin.host.PluginHost
import org.example.kotlineditor.plugin.host.PluginProblem
import org.example.kotlineditor.rpc.RpcClient
import org.example.kotlineditor.ui.loadPlugin
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private const val MANIFEST = "plugin.yaml"

/** A plugin of the plugins folder, as the Plugins settings list it. */
class PluginEntry(
    /** Folder name, the id of the plugin. */
    val id: String,
    /** Name from its manifest (the id when the manifest is unreadable). */
    val name: String,
    /** Version from its manifest, or empty. */
    val version: String,
    /** Whether it runs. */
    val running: Boolean,
    /** Why it is off, in words; empty when it runs. */
    val offBecause: String,
)

/**
 * The plugins installed in [directory] (`~/.kotlin-editor/plugins/<id>/` by default), loaded into the running IDE
 * and unloaded without a restart. The operations wait for the garbage collector when they unload: not on the UI thread.
 */
@Stable
class ClientPlugins(
    private val directory: File = defaultDirectory(),
    /** See [PluginHost]: lets the UI drop the code of an unloaded plugin before it is checked. */
    beforeCollect: () -> Unit = {},
) {
    private val host = PluginHost(::loadPlugin, beforeCollect)
    private val problems = linkedMapOf<String, PluginProblem>()

    /** The plugins of the folder, running or not; read by the Plugins settings. */
    var entries: List<PluginEntry> by mutableStateOf(emptyList())
        private set

    /** The ids of the plugins that run. */
    val loaded: List<String> get() = host.loadedIds

    /** Why the plugins that do not run are off, by folder name. */
    val offBecause: Map<String, PluginProblem> get() = problems.toMap()

    /** Loads every plugin of the directory; the ones that cannot start are listed in [offBecause]. */
    fun start() {
        problems.clear()
        host.loadAll(directory).forEach(::remember)
        refresh()
    }

    /**
     * Installs the plugin archive [archive] (a zip of `plugin.yaml`, `client.jar`, `server.jar`...) into the folder
     * named by its id, replacing and reloading the version that runs. Returns what went wrong, or null.
     */
    fun install(archive: File): String? {
        val manifest = readManifest(archive) ?: return "${archive.name} is not a plugin: no valid plugin.yaml"
        val folder = File(directory, manifest.id)
        if (manifest.id in host.loadedIds) host.unload(manifest.id)
        folder.deleteRecursively()
        folder.mkdirs()
        extract(archive, folder)
        problems.remove(manifest.id)
        remember(manifest.id, host.load(folder))
        refresh()
        return problems[manifest.id]?.let(::describe)
    }

    /** Turns the plugin of folder [id] off (unloading it) or on (loading it, and forgetting an earlier crash). */
    fun setEnabled(id: String, enabled: Boolean) {
        val folder = File(directory, id)
        if (enabled) {
            host.enable(folder)
            problems.remove(id)
            remember(id, host.load(folder))
        } else {
            host.disable(folder)
            problems[id] = PluginProblem.Disabled
        }
        refresh()
    }

    /**
     * Installs the server halves of the running plugins on the server of [client]. A plugin whose server half the
     * server cannot take is unloaded here too: a plugin present on one side only does not run half-working.
     */
    suspend fun syncServer(client: RpcClient) {
        val withServer = host.manifests.filterValues { it.server != null }
        val halves =
            withServer.map { (id, manifest) ->
                val folder = File(directory, id)
                ServerHalfToSend(id, File(folder, MANIFEST).readText(), File(folder, "server.jar"))
            }
        val failures = syncServerHalves(client, halves)
        for ((id, reason) in failures) {
            host.unload(id)
            problems[id] = PluginProblem.ServerUnavailable(reason)
        }
        if (failures.isNotEmpty()) refresh()
    }

    /**
     * Keeps the server holding the server halves: at the first connection and at each reconnection, since a server
     * that restarted may have lost them or come back with another version. Never returns; cancel it to stop.
     */
    suspend fun keepServerInSync(client: RpcClient) {
        client.connections.collect {
            try {
                syncServer(client)
            } catch (@Suppress("SwallowedException") broken: IOException) {
                // The link broke while syncing: the next connection syncs again.
            }
        }
    }

    /** Unloads then loads again the plugin of folder [id], to take a new version. */
    fun reload(id: String): UnloadOutcome? {
        val outcome = if (id in host.loadedIds) host.unload(id) else null
        if (outcome != UnloadOutcome.RestartRequired) remember(id, host.load(File(directory, id)))
        refresh()
        return outcome
    }

    /** Unloads plugin [id]; [UnloadOutcome.RestartRequired] when something still holds its code. */
    fun unload(id: String): UnloadOutcome = host.unload(id).also { refresh() }

    /** Unloads every plugin, as the IDE closes; does not wait for the garbage collector. */
    fun stop() {
        host.unloadAll()
    }

    private fun remember(folder: String, result: LoadResult) {
        val problem = result.problem
        if (problem == null) problems.remove(folder) else problems[folder] = problem
    }

    private fun refresh() {
        val folders = directory.listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }
        entries = folders.map(::entryOf)
    }

    private fun entryOf(folder: File): PluginEntry {
        val manifest = runCatching { PluginManifest.parse(File(folder, MANIFEST).readText()) }.getOrNull()
        val running = folder.name in host.loadedIds
        val problem = problems[folder.name]
        val reason = if (running || problem == null) "" else describe(problem)
        return PluginEntry(folder.name, manifest?.name ?: folder.name, manifest?.version.orEmpty(), running, reason)
    }

    private companion object {
        fun defaultDirectory() = File(System.getProperty("user.home"), ".kotlin-editor/plugins")
    }
}

private fun readManifest(archive: File): PluginManifest? =
    runCatching {
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry(MANIFEST) ?: return null
            PluginManifest.parse(zip.getInputStream(entry).readBytes().decodeToString())
        }
    }.getOrNull()

/**
 * Extracts the files at the root of [archive] into [folder], never a path outside of it: an entry that is nested, or
 * whose name would leave the folder (`..`, an absolute path), is skipped.
 */
internal fun extract(archive: File, folder: File) {
    val base = folder.canonicalFile
    ZipFile(archive).use { zip ->
        for (entry in zip.entries().asSequence().filter(::isRootFile)) {
            val target = File(base, entry.name)
            val inside = target.canonicalPath.startsWith(base.path + File.separator)
            if (!inside) continue
            target.outputStream().use { out -> zip.getInputStream(entry).copyTo(out) }
        }
    }
}

private fun isRootFile(entry: ZipEntry): Boolean {
    val name = entry.name
    val nested = '/' in name || '\\' in name
    val climbs = name.startsWith("..")
    val regular = !entry.isDirectory && name.isNotEmpty()
    return regular && !nested && !climbs
}

private fun describe(problem: PluginProblem): String =
    when (problem) {
        is PluginProblem.InvalidManifest -> "Invalid manifest: ${problem.reason}"
        is PluginProblem.ApiMismatch -> "Built for plugin API ${problem.actual}, this IDE offers ${problem.expected}"
        PluginProblem.Crashed -> "Its last start did not finish; enable it to try again"
        PluginProblem.Disabled -> "Disabled"
        is PluginProblem.MissingDependency -> "Needs the plugin ${problem.id}"
        is PluginProblem.Failed -> "Failed to start: ${problem.reason}"
        is PluginProblem.ServerUnavailable -> "Its server half could not be installed: ${problem.reason}"
    }
