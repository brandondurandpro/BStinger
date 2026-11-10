package org.example.kotlineditor.state

import org.example.kotlineditor.plugin.core.UnloadOutcome
import org.example.kotlineditor.plugin.host.LoadResult
import org.example.kotlineditor.plugin.host.PluginHost
import org.example.kotlineditor.plugin.host.PluginProblem
import org.example.kotlineditor.rpc.RpcClient
import org.example.kotlineditor.ui.loadPlugin
import java.io.File
import java.io.IOException

/**
 * The plugins installed in [directory] (`~/.kotlin-editor/plugins/<id>/` by default), loaded into the running IDE
 * and unloaded without a restart.
 */
class ClientPlugins(
    private val directory: File = defaultDirectory(),
    /** See [PluginHost]: lets the UI drop the code of an unloaded plugin before it is checked. */
    beforeCollect: () -> Unit = {},
) {
    private val host = PluginHost(::loadPlugin, beforeCollect)
    private val problems = linkedMapOf<String, PluginProblem>()

    /** The ids of the plugins that run. */
    val loaded: List<String> get() = host.loadedIds

    /** Why the plugins that do not run are off, by folder name. */
    val offBecause: Map<String, PluginProblem> get() = problems.toMap()

    /** Loads every plugin of the directory; the ones that cannot start are listed in [offBecause]. */
    fun start() {
        problems.clear()
        host.loadAll(directory).forEach(::remember)
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
                ServerHalfToSend(id, File(folder, "plugin.yaml").readText(), File(folder, "server.jar"))
            }
        val failures = syncServerHalves(client, halves)
        for ((id, reason) in failures) {
            host.unload(id)
            problems[id] = PluginProblem.ServerUnavailable(reason)
        }
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
        return outcome
    }

    /** Unloads plugin [id]; [UnloadOutcome.RestartRequired] when something still holds its code. */
    fun unload(id: String): UnloadOutcome = host.unload(id)

    /** Unloads every plugin, as the IDE closes; does not wait for the garbage collector. */
    fun stop() {
        host.unloadAll()
    }

    private fun remember(folder: String, result: LoadResult) {
        val problem = result.problem
        if (problem == null) problems.remove(folder) else problems[folder] = problem
    }

    private companion object {
        fun defaultDirectory() = File(System.getProperty("user.home"), ".kotlin-editor/plugins")
    }
}
