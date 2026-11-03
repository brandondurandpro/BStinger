package org.example.kotlineditor.server

import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.telemetry.HookedTelemetry
import org.example.kotlineditor.telemetry.Side
import org.example.kotlineditor.telemetry.SwitchableTelemetry
import org.example.kotlineditor.telemetry.Telemetry
import org.example.kotlineditor.telemetry.TelemetryHook
import org.example.kotlineditor.telemetry.otel.ExportConfig
import org.example.kotlineditor.telemetry.otel.OtelHook
import org.example.kotlineditor.telemetry.telemetryForbidden
import java.io.Closeable

/**
 * The telemetry of the server: off until a client says the user agreed ([configure]), never on when the environment
 * forbids it. The export goes to the collector of this machine's environment ([ExportConfig.endpointOf]).
 */
class ServerTelemetry(
    private val forbidden: Boolean = telemetryForbidden(),
    private val hookOf: (instanceId: String) -> TelemetryHook = { instanceId ->
        OtelHook(
            ExportConfig(
                endpoint = ExportConfig.endpointOf(),
                serviceName = "kotlin-editor-server",
                serviceVersion = Protocol.APP_VERSION,
                instanceId = instanceId,
            ),
        )
    },
) : Closeable {
    /** What the server measures with. */
    val telemetry = SwitchableTelemetry()

    private var instance: String? = null
    private var hook: TelemetryHook? = null

    /** The user agreed ([enabled]) or not, as installation [instanceId]: switched off, what is queued is dropped. */
    @Synchronized
    fun configure(enabled: Boolean, instanceId: String?) {
        val wanted = instanceId?.takeIf { enabled && !forbidden && it.isNotBlank() }
        if (wanted == instance) return
        instance = wanted
        val created = wanted?.let(hookOf)
        hook = created
        val next: Telemetry = if (created == null) Telemetry.Disabled else HookedTelemetry(listOf(created), Side.Server)
        telemetry.switchTo(next)
    }

    /** The server stops: what is queued is sent, waiting at most the export's timeout. */
    @Synchronized
    override fun close() {
        (hook as? Closeable)?.close()
        hook = null
        instance = null
        telemetry.switchTo(Telemetry.Disabled)
    }
}
