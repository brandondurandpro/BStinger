package org.example.kotlineditor.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.plugin.core.PluginClassLoader
import org.example.kotlineditor.plugin.core.PluginManifest
import org.example.kotlineditor.plugin.core.SERVER_VISIBLE
import org.example.kotlineditor.plugin.core.UnloadOutcome
import org.example.kotlineditor.plugin.core.outcomeOf
import org.example.kotlineditor.plugin.server.PluginRequest
import org.example.kotlineditor.plugin.server.ServerContributions
import org.example.kotlineditor.plugin.server.ServerPlugin
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.MethodRegistry
import org.example.kotlineditor.rpc.MethodSpec
import org.example.kotlineditor.rpc.string
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.Base64

private const val MANIFEST = "plugin.yaml"
private const val SERVER_JAR = "server.jar"
private const val DIGEST = "server.sha256"
private const val LOADING_MARKER = ".loading"
private const val ID = "id"
private const val VERSION = "version"
private const val SHA256 = "sha256"

/** A server half that runs. */
private class RunningHalf(
    val id: String,
    val version: String,
    val sha256: String,
    val registration: AutoCloseable,
    val methods: List<MethodSpec>,
    val cleanups: List<AutoCloseable>,
    val loader: PluginClassLoader,
)

/**
 * The server halves of the plugins, installed in [directory] (`<id>/server.jar` and its manifest): loaded when the
 * server starts, installed and unloaded while it runs, each in a class loader that only sees the plugin API. The
 * handlers of their methods are put in [handlers], the map the sessions read.
 */
internal class ServerPluginHost(private val directory: File, private val handlers: MutableMap<MethodSpec, Handler>) {
    private val running = linkedMapOf<String, RunningHalf>()

    /** Loads what is installed; a half whose last start did not finish stays off. */
    @Synchronized
    fun loadInstalled() {
        val folders = directory.listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }
        for (folder in folders) {
            val marker = File(folder, LOADING_MARKER)
            if (marker.exists() || !File(folder, SERVER_JAR).isFile) continue
            marker.writeText("")
            val started = runCatching { start(folder) }.isSuccess
            if (started) marker.delete()
        }
    }

    /** The installed halves that run: id, version and SHA-256 of the jar. */
    @Synchronized
    fun list(): JsonElement =
        buildJsonArray {
            for (half in running.values) {
                add(
                    buildJsonObject {
                        put(ID, half.id)
                        put(VERSION, half.version)
                        put(SHA256, half.sha256)
                    },
                )
            }
        }

    /**
     * Installs the half [manifestText] describes from [jar], replacing the one of the same id. Refused when [sha256]
     * is not the jar's, so a damaged transfer never runs.
     */
    @Synchronized
    fun install(manifestText: String, jar: ByteArray, sha256: String) {
        val manifest = PluginManifest.parse(manifestText)
        val actual = sha256Of(jar)
        require(actual.equals(sha256, ignoreCase = true)) { "The jar of ${manifest.id} is damaged" }
        require(manifest.server != null) { "${manifest.id} has no server half" }
        if (manifest.id in running) unloadLocked(manifest.id)
        val folder = File(directory, manifest.id).apply { mkdirs() }
        File(folder, MANIFEST).writeText(manifestText)
        File(folder, SERVER_JAR).writeBytes(jar)
        File(folder, DIGEST).writeText(actual)
        start(folder)
    }

    /** Unloads [id] and deletes it; [UnloadOutcome.RestartRequired] when something still holds its code. */
    @Synchronized
    fun unload(id: String): UnloadOutcome {
        val outcome = unloadLocked(id)
        File(directory, id).deleteRecursively()
        return outcome
    }

    /** Unloads everything, as the server stops. */
    @Synchronized
    fun close() {
        running.keys.toList().forEach { unloadLocked(it) }
    }

    private fun unloadLocked(id: String): UnloadOutcome {
        val reference = shutDown(id)
        return outcomeOf(reference)
    }

    // Nothing of the plugin stays referenced by this frame once it returns, or its class loader could not be collected.
    private fun shutDown(id: String): WeakReference<PluginClassLoader> {
        val half = checkNotNull(running.remove(id)) { "$id is not installed" }
        half.methods.forEach { handlers.remove(it) }
        half.registration.close()
        half.cleanups.forEach { runCatching { it.close() } }
        val reference = WeakReference(half.loader)
        half.loader.close()
        return reference
    }

    private fun start(folder: File) {
        val manifest = PluginManifest.parse(File(folder, MANIFEST).readText())
        val jar = File(folder, SERVER_JAR)
        val loader = PluginClassLoader(listOf(jar), ServerPluginHost::class.java.classLoader, SERVER_VISIBLE)
        try {
            running[manifest.id] = register(manifest, loader, File(folder, DIGEST).readText())
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            loader.close()
            throw failure
        }
    }

    private fun register(manifest: PluginManifest, loader: PluginClassLoader, sha256: String): RunningHalf {
        val className = requireNotNull(manifest.server) { "${manifest.id} has no server half" }
        val plugin = loader.loadClass(className).getDeclaredConstructor().newInstance() as ServerPlugin
        val contributions = ServerContributions()
        plugin.register(contributions)
        val declared = contributions.handlers()
        val prefix = "plugin/${manifest.id}/"
        for (method in declared.keys) {
            require(method.rpcName.startsWith(prefix)) { "${method.rpcName} is not under $prefix" }
        }
        val registration = MethodRegistry.register(declared.keys)
        for ((method, handler) in declared) handlers[method] = adapted(handler)
        return RunningHalf(
            manifest.id,
            manifest.version,
            sha256,
            registration,
            declared.keys.toList(),
            contributions.cleanups(),
            loader,
        )
    }

    private fun adapted(handler: suspend (PluginRequest) -> JsonElement): Handler =
        { handler(PluginRequest(params, session)) }
}

private fun sha256Of(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** The methods `plugin/list`, `plugin/install` and `plugin/unload`. */
internal fun pluginHandlers(host: ServerPluginHost): Map<MethodSpec, Handler> =
    mapOf(
        Method.PluginList to { host.list() },
        Method.PluginInstall to {
            val jar = Base64.getDecoder().decode(params.string("jar"))
            nothing { host.install(params.string("manifest"), jar, params.string(SHA256)) }
        },
        Method.PluginUnload to {
            JsonPrimitive(host.unload(params.string(ID)).name)
        },
    )
