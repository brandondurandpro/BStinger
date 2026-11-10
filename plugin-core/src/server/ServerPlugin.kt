package org.example.kotlineditor.plugin.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.example.kotlineditor.rpc.PluginMethod

/** The server half of a plugin: the class named by `server` in its manifest implements it. */
interface ServerPlugin {
    /** Called once when the half loads, on the machine that holds the project; what it registers goes with it. */
    fun register(contributions: ServerContributions)
}

/** A request to a method of a plugin: its named params, and the session of the client that sent it. */
class PluginRequest(
    /** The named params. */
    val params: JsonObject,
    /** The session of the client. */
    val session: String,
)

/** What a [ServerPlugin] registers. */
class ServerContributions {
    private val methods = linkedMapOf<PluginMethod, suspend (PluginRequest) -> JsonElement>()
    private val cleanups = mutableListOf<AutoCloseable>()

    /** Answers [method], whose name must be under the id of the plugin. */
    fun handle(method: PluginMethod, handler: suspend (PluginRequest) -> JsonElement) {
        require(method !in methods) { "${method.rpcName} has two handlers" }
        methods[method] = handler
    }

    /** Runs [cleanup] when the plugin unloads (stop a process, close a file). */
    fun onUnload(cleanup: AutoCloseable) {
        cleanups += cleanup
    }

    /** The handlers, by method. */
    fun handlers(): Map<PluginMethod, suspend (PluginRequest) -> JsonElement> = methods.toMap()

    /** The cleanups, last registered first. */
    fun cleanups(): List<AutoCloseable> = cleanups.reversed()
}
