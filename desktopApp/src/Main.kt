package org.example.kotlineditor

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.example.kotlineditor.remote.JvmRemoteFactory
import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.rpc.RpcClient
import org.example.kotlineditor.rpc.appServices
import org.example.kotlineditor.server.EditorServer
import org.example.kotlineditor.server.LocalServices
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.FilePreferenceStore
import org.example.kotlineditor.syntax.treesitter.GrammarStore
import org.example.kotlineditor.telemetry.ClientTelemetry
import org.example.kotlineditor.telemetry.HookedTelemetry
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.Side
import org.example.kotlineditor.telemetry.Telemetry
import org.example.kotlineditor.telemetry.otel.ExportConfig
import org.example.kotlineditor.telemetry.otel.OtelHook
import java.io.File
import java.lang.management.ManagementFactory

/**
 * The IDE is a client of the Kotlin Editor server. By default the server is embedded (started on loopback with a
 * random token); `--server host:port --token secret` (or KOTLIN_EDITOR_SERVER / KOTLIN_EDITOR_TOKEN) uses a remote one.
 * Remote Development from the welcome screen installs and tunnels to a server on another machine.
 */
fun main(args: Array<String>) {
    fun option(name: String) = args.indexOf("--$name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

    val remote = option("server") ?: System.getenv("KOTLIN_EDITOR_SERVER")
    val embedded = if (remote == null) EditorServer(LocalServices()).start() else null
    val client =
        if (embedded != null) {
            RpcClient("127.0.0.1", embedded.port, embedded.token)
        } else {
            val host = remote!!.substringBeforeLast(':')
            val port = remote.substringAfterLast(':', "").toIntOrNull() ?: Protocol.DEFAULT_PORT
            RpcClient(host, port, option("token") ?: System.getenv("KOTLIN_EDITOR_TOKEN").orEmpty())
        }
    val services = client.appServices()
    // Only an embedded server can be returned to when the remote session ends.
    val localServices = services.takeIf { embedded != null }

    val exporter = DesktopExporter()
    application {
        val scope = rememberCoroutineScope()
        val state =
            remember {
                AppState(
                    services,
                    scope,
                    FilePreferenceStore(),
                    grammarStore(),
                    localServices,
                    JvmRemoteFactory,
                    telemetryOf = exporter::telemetryOf,
                )
            }
        LaunchedEffect(Unit) { measureStartup() }
        Window(
            onCloseRequest = {
                state.exit {
                    client.close()
                    embedded?.close()
                    exporter.close()
                    exitApplication()
                }
            },
            title = state.title,
            state = rememberWindowState(size = DpSize(1280.dp, 820.dp)),
        ) {
            App(state)
        }
    }
}

// From the start of the JVM to the first frame drawn.
private suspend fun measureStartup() {
    repeat(2) { withFrameNanos {} }
    val uptime = ManagementFactory.getRuntimeMXBean().uptime
    ClientTelemetry.record(Operation.AppStartup, uptime * NANOS_PER_MILLI)
}

private const val NANOS_PER_MILLI = 1_000_000L

/** The telemetry of the IDE window, exported to OpenTelemetry once the user agreed. */
private class DesktopExporter : AutoCloseable {
    private var hook: OtelHook? = null

    fun telemetryOf(instanceId: String): Telemetry {
        val config =
            ExportConfig(
                endpoint = ExportConfig.endpointOf(),
                serviceName = "kotlin-editor-desktop",
                serviceVersion = Protocol.APP_VERSION,
                instanceId = instanceId,
            )
        val created = OtelHook(config)
        hook = created
        return HookedTelemetry(listOf(created), Side.Client)
    }

    // What is queued leaves before the IDE exits, waiting at most the export's timeout.
    override fun close() {
        hook?.close()
    }
}

// Grammars are downloaded on the machine running the IDE, next to its preferences: `~/.kotlin-editor/tree-sitter`.
private fun grammarStore() = GrammarStore(File(System.getProperty("user.home"), ".kotlin-editor/tree-sitter"))
