package org.example.kotlineditor.state

import org.example.kotlineditor.plugin.host.LoadResult
import org.example.kotlineditor.plugin.host.PluginHost
import org.example.kotlineditor.plugin.host.PluginProblem
import org.example.kotlineditor.plugin.host.UnloadOutcome
import org.example.kotlineditor.ui.loadPlugin
import java.io.File

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

    /** Unloads then loads again the plugin of folder [id], to take a new version. */
    fun reload(id: String): UnloadOutcome? {
        val outcome = if (id in host.loadedIds) host.unload(id) else null
        if (outcome != UnloadOutcome.RestartRequired) remember(id, host.load(File(directory, id)))
        return outcome
    }

    /** Unloads plugin [id]; [UnloadOutcome.RestartRequired] when something still holds its code. */
    fun unload(id: String): UnloadOutcome = host.unload(id)

    /** Unloads every plugin, as the IDE closes. */
    fun stop() {
        host.loadedIds.reversed().forEach { host.unload(it) }
    }

    private fun remember(folder: String, result: LoadResult) {
        val problem = result.problem
        if (problem == null) problems.remove(folder) else problems[folder] = problem
    }

    private companion object {
        fun defaultDirectory() = File(System.getProperty("user.home"), ".kotlin-editor/plugins")
    }
}
