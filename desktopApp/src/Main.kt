package org.example.kotlineditor

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.example.kotlineditor.remote.JvmRemoteFactory
import org.example.kotlineditor.remote.ServerPathPicker
import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.rpc.RpcClient
import org.example.kotlineditor.rpc.appServices
import org.example.kotlineditor.server.EditorServer
import org.example.kotlineditor.server.LocalServices
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.FilePreferenceStore
import org.example.kotlineditor.syntax.treesitter.GrammarStore
import java.io.File
import javax.swing.UIManager

/**
 * The IDE is a client of the Kotlin Editor server. By default the server is embedded (started on loopback with a
 * random token); `--server host:port --token secret` (or KOTLIN_EDITOR_SERVER / KOTLIN_EDITOR_TOKEN) uses a remote one.
 * Remote Development from the welcome screen installs and tunnels to a server on another machine.
 */
fun main(args: Array<String>) {
    fun option(name: String) = args.indexOf("--$name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    // Native look for the Swing dialogs.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

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
    // A remote project lives on the server's disk: ask for its path instead of browsing the local disk.
    val picker = if (embedded != null) SwingDirectoryPicker() else ServerPathPicker(remote!!)
    val services = client.appServices(picker)
    // Only an embedded server can be returned to when the remote session ends.
    val localServices = services.takeIf { embedded != null }

    application {
        val scope = rememberCoroutineScope()
        val state =
            remember {
                AppState(services, scope, FilePreferenceStore(), grammarStore(), localServices, JvmRemoteFactory)
            }
        Window(
            onCloseRequest = {
                state.exit {
                    client.close()
                    embedded?.close()
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

// Grammars are downloaded on the machine running the IDE, next to its preferences: `~/.kotlin-editor/tree-sitter`.
private fun grammarStore() = GrammarStore(File(System.getProperty("user.home"), ".kotlin-editor/tree-sitter"))
