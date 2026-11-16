package org.example.kotlineditor.rpc

import kotlinx.serialization.json.put
import org.example.kotlineditor.model.TelemetrySettings

/** [TelemetrySettings] of the server. */
class RemoteTelemetrySettings(private val client: RpcClient) : TelemetrySettings {
    override suspend fun configure(enabled: Boolean, instanceId: String?) {
        client.call(Method.TelemetryConfig) {
            put("enabled", enabled)
            if (instanceId != null) put("instanceId", instanceId)
        }
    }
}
