package org.example.kotlineditor.rpc

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.TerminalKey
import org.example.kotlineditor.model.TerminalKeyEvent
import org.example.kotlineditor.model.TerminalLauncher
import org.example.kotlineditor.model.TerminalMouseEvent
import org.example.kotlineditor.model.TerminalProcess
import org.example.kotlineditor.model.TerminalSnapshot
import java.io.IOException

private const val DIRECTORY = "directory"
private const val TERMINAL = "terminal"
private const val COLUMNS = "columns"

/**
 * Terminal running on the server. The request id of TerminalStart is the id of its snapshot stream, registered
 * before the request is sent so no screen can arrive before the client listens. Keys, pastes, resizes and scrolls
 * go through a queue so they reach the terminal in the order they were typed.
 */
class RemoteTerminalLauncher(private val client: RpcClient) : TerminalLauncher {
    override fun start(
        directory: String,
        columns: Int,
        rows: Int,
        cellWidthPx: Int,
        cellHeightPx: Int,
    ): TerminalProcess {
        val id = client.newId()
        val screens = client.openStream(id)
        val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        client.scope.launch {
            try {
                client.call(Method.TerminalStart, id = id) {
                    put(DIRECTORY, directory)
                    size(columns, rows, cellWidthPx, cellHeightPx)
                }
                for (command in commands) command()
            } catch (_: IOException) {
                commands.close()
                client.closeStream(id)
            }
        }
        return object : TerminalProcess {
            override val output: Flow<TerminalSnapshot> = flow { for (snapshot in screens) emit(snapshot) }

            override fun write(bytes: ByteArray) {
                command(Method.TerminalWrite) { terminalInput(bytes) }
            }

            override fun sendKey(event: TerminalKeyEvent) {
                command(Method.TerminalKey) { put("event", event.toJson()) }
            }

            override fun mouse(event: TerminalMouseEvent) {
                command(Method.TerminalMouse) { put("event", event.toJson()) }
            }

            override fun paste(text: String) {
                command(Method.TerminalPaste) { put("text", text) }
            }

            override fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
                command(Method.TerminalResize) { size(columns, rows, cellWidthPx, cellHeightPx) }
            }

            override fun scroll(delta: Int) {
                command(Method.TerminalScroll) { put("delta", delta) }
            }

            override fun clear() {
                command(Method.TerminalClear)
            }

            override fun destroy() {
                commands.close()
                client.scope.launch { runCatching { client.call(Method.TerminalDestroy) { put(TERMINAL, id) } } }
                client.closeStream(id)
            }

            // Queued: commands reach the terminal in the order they were typed.
            private fun command(method: Method, params: JsonObjectBuilder.() -> Unit = {}) {
                commands.trySend {
                    client.call(method) {
                        put(TERMINAL, id)
                        params()
                    }
                }
            }
        }
    }
}

private fun JsonObjectBuilder.size(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
    put(COLUMNS, columns)
    put("rows", rows)
    put("cellWidthPx", cellWidthPx)
    put("cellHeightPx", cellHeightPx)
}
