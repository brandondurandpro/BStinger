package org.example.kotlineditor.plugin.host

import org.example.kotlineditor.plugin.ClientContributions
import org.example.kotlineditor.plugin.ClientPlugin
import org.example.kotlineditor.plugin.IdePlugin
import java.io.File
import java.lang.ref.WeakReference

private const val MANIFEST = "plugin.yaml"
private const val CLIENT_JAR = "client.jar"
private const val SHARED_JAR = "shared.jar"
private const val LOADING_MARKER = ".loading"
private const val DISABLED_MARKER = ".disabled"
private const val COLLECTION_ATTEMPTS = 10
private const val COLLECTION_PAUSE_MILLIS = 50L

/** Why a plugin is not running. */
sealed interface PluginProblem {
    /** The folder has no readable manifest. */
    class InvalidManifest(val reason: String) : PluginProblem

    /** The plugin was built against another API. */
    class ApiMismatch(val expected: Int, val actual: Int) : PluginProblem

    /** The last start did not finish: the plugin is kept off until the user enables it. */
    object Crashed : PluginProblem

    /** The user turned the plugin off. */
    object Disabled : PluginProblem

    /** A plugin it needs is not loaded. */
    class MissingDependency(val id: String) : PluginProblem

    /** Its code failed while loading. */
    class Failed(val reason: String) : PluginProblem
}

/** What [PluginHost.unload] found once the plugin's class loader should be gone. */
enum class UnloadOutcome {
    /** The class loader was collected. */
    Clean,

    /** Something still holds the class loader: running two versions side by side is unsafe, restart the IDE. */
    RestartRequired,
}

/** A plugin that is running. */
class LoadedPlugin internal constructor(
    /** Its manifest. */
    val manifest: PluginManifest,
    internal val plugin: IdePlugin,
    internal val registration: AutoCloseable,
    internal val cleanups: List<AutoCloseable>,
    internal val loader: PluginClassLoader,
)

/** The outcome of loading a folder: the plugin running, or what keeps it off. */
class LoadResult(
    /** The plugin, when it runs. */
    val loaded: LoadedPlugin?,
    /** What keeps it off, when it does not run. */
    val problem: PluginProblem?,
)

/**
 * Loads plugins from folders `<id>/` holding `plugin.yaml` and `client.jar`, each in a class loader of its own, and
 * unloads them without restarting. [install] puts what a plugin contributed into the IDE and returns what removes it.
 */
class PluginHost(
    /** Puts a plugin's contributions into the IDE; the result removes them. */
    private val install: (IdePlugin) -> AutoCloseable,
    /**
     * Called before each attempt to collect an unloaded class loader: the UI still shows the removed contributions
     * until it recomposes, and holds their code until then. A UI under test advances its clock here.
     */
    private val beforeCollect: () -> Unit = {},
) {
    private val running = linkedMapOf<String, LoadedPlugin>()

    /** The ids of the running plugins, in load order. */
    val loadedIds: List<String> get() = running.keys.toList()

    /** Loads the plugin of [folder]; a plugin that crashed its last start, or was disabled, is not started. */
    fun load(folder: File): LoadResult {
        val manifest =
            runCatching { PluginManifest.parse(File(folder, MANIFEST).readText()) }
                .getOrElse { return refused(PluginProblem.InvalidManifest(it.message.orEmpty())) }
        val problem = problemOf(folder, manifest)
        if (problem != null) return refused(problem)
        val marker = File(folder, LOADING_MARKER)
        marker.writeText(manifest.version)
        val result = start(folder, manifest)
        // A start that threw or hung leaves the marker, and the next IDE start keeps the plugin off.
        if (result.loaded != null) marker.delete()
        return result
    }

    /** Loads every plugin of [directory] (dependencies first is the caller's order: sorted by id). */
    fun loadAll(directory: File): Map<String, LoadResult> {
        val folders = directory.listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }
        return folders.associate { it.name to load(it) }
    }

    /**
     * Unloads [id]: its contributions go, its cleanups run, its class loader is dropped and checked. Waits a moment
     * for the garbage collector, so call it off the UI thread.
     */
    fun unload(id: String): UnloadOutcome {
        val reference = shutDown(id)
        return if (collected(reference)) UnloadOutcome.Clean else UnloadOutcome.RestartRequired
    }

    // Nothing of the plugin stays referenced by this frame once it returns, or its class loader could not be collected.
    private fun shutDown(id: String): WeakReference<PluginClassLoader> {
        val plugin = checkNotNull(running.remove(id)) { "$id is not loaded" }
        plugin.registration.close()
        plugin.cleanups.forEach { runCatching { it.close() } }
        val reference = WeakReference(plugin.loader)
        plugin.loader.close()
        return reference
    }

    /** Keeps [folder]'s plugin off at the next starts; unloads it if it runs. */
    fun disable(folder: File) {
        File(folder, DISABLED_MARKER).writeText("")
        running.keys.firstOrNull { it == folder.name }?.let { unload(it) }
    }

    /** Lets a plugin kept off by a crash or by the user start again. */
    fun enable(folder: File) {
        File(folder, DISABLED_MARKER).delete()
        File(folder, LOADING_MARKER).delete()
    }

    private fun refused(problem: PluginProblem) = LoadResult(null, problem)

    private fun problemOf(folder: File, manifest: PluginManifest): PluginProblem? =
        when {
            File(folder, DISABLED_MARKER).exists() -> PluginProblem.Disabled
            File(folder, LOADING_MARKER).exists() -> PluginProblem.Crashed
            manifest.apiVersion != API_VERSION -> PluginProblem.ApiMismatch(API_VERSION, manifest.apiVersion)
            else -> manifest.dependencies.firstOrNull { it !in running }?.let { PluginProblem.MissingDependency(it) }
        }

    private fun start(folder: File, manifest: PluginManifest): LoadResult {
        val jars = listOf(File(folder, CLIENT_JAR), File(folder, SHARED_JAR)).filter { it.isFile }
        val loader = PluginClassLoader(jars, PluginHost::class.java.classLoader)
        return try {
            val loaded = register(manifest, loader)
            running[manifest.id] = loaded
            LoadResult(loaded, null)
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            loader.close()
            LoadResult(null, PluginProblem.Failed(failure.message ?: failure.javaClass.name))
        }
    }

    private fun register(manifest: PluginManifest, loader: PluginClassLoader): LoadedPlugin {
        val className = requireNotNull(manifest.client) { "${manifest.id} has no client half" }
        val entry = loader.loadClass(className).getDeclaredConstructor().newInstance()
        val clientPlugin = entry as ClientPlugin
        val contributions = ClientContributions()
        clientPlugin.register(contributions)
        val plugin = contributions.toPlugin(manifest.id)
        val registration = install(plugin)
        return LoadedPlugin(manifest, plugin, registration, contributions.cleanups(), loader)
    }

    // Collection is not immediate: ask for it a few times before concluding that something holds the class loader.
    private fun collected(reference: WeakReference<PluginClassLoader>): Boolean {
        repeat(COLLECTION_ATTEMPTS) {
            beforeCollect()
            System.gc()
            if (reference.get() == null) return true
            Thread.sleep(COLLECTION_PAUSE_MILLIS)
        }
        return reference.get() == null
    }
}
