package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.PluginServer

// The methods `plugin/*` are declared by the plugins; the IDE client only carries their calls through.

/** [PluginServer] of the server. */
class RemotePluginServer(private val client: RpcClient) : PluginServer {
    override suspend fun call(method: MethodSpec, params: JsonObject): JsonElement =
        client.call(method) { params.forEach { (name, value) -> put(name, value) } }
}
