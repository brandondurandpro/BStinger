package org.example.kotlineditor.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.example.kotlineditor.rpc.MethodSpec
import java.io.IOException

/** The link a plugin's client half uses to call the methods its server half answers. */
interface PluginServer {
    /** Calls [method] (a [org.example.kotlineditor.rpc.PluginMethod] of the plugin) with the named [params]. */
    suspend fun call(method: MethodSpec, params: JsonObject = JsonObject(emptyMap())): JsonElement

    /** No server: every call fails. */
    object None : PluginServer {
        override suspend fun call(method: MethodSpec, params: JsonObject): JsonElement =
            throw IOException("There is no server to call ${method.rpcName} on")
    }
}
