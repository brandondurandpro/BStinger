package org.example.kotlineditor.terminal.server

import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.MethodSpec
import org.example.kotlineditor.rpc.int
import org.example.kotlineditor.rpc.keyEvent
import org.example.kotlineditor.rpc.long
import org.example.kotlineditor.rpc.read
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.rpc.terminalInput
import org.example.kotlineditor.server.Handler
import org.example.kotlineditor.server.LocalServices
import org.example.kotlineditor.server.Request
import org.example.kotlineditor.server.ServerContribution
import org.example.kotlineditor.server.SessionKey
import org.example.kotlineditor.server.nothing
import java.util.concurrent.ConcurrentHashMap

private const val DIRECTORY = "directory"
private const val TERMINAL = "terminal"

private val TERMINALS = SessionKey("terminal") { TerminalRegistry(it.push) }

/** Found by the server through `META-INF/services`. */
class TerminalServerContribution : ServerContribution {
    override fun handlers(services: LocalServices) = terminalHandlers()
}

/** Terminals of a session, keyed by the id of their snapshot stream. */
internal class TerminalRegistry(private val push: (Frame) -> Unit) : AutoCloseable {
    private val terminals = ConcurrentHashMap<Long, ServerTerminal>()

    fun start(id: Long, directory: String, columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        terminals.remove(id)?.destroy()
        terminals[id] =
            ServerTerminal(id, directory, columns, rows, cellWidthPx, cellHeightPx, push)
    }

    operator fun get(id: Long): ServerTerminal? = terminals[id]

    fun stop(id: Long) {
        terminals.remove(id)?.destroy()
    }

    fun stopAll() {
        terminals.values.forEach { it.destroy() }
        terminals.clear()
    }

    override fun close() = stopAll()
}

internal fun terminalHandlers(): Map<MethodSpec, Handler> =
    mapOf(
        Method.TerminalStart to {
            nothing {
                state(TERMINALS).start(
                    id,
                    params.string(DIRECTORY),
                    params.int("columns"),
                    params.int("rows"),
                    params.int("cellWidthPx"),
                    params.int("cellHeightPx"),
                )
            }
        },
        Method.TerminalWrite to { nothing { terminalOrFail().write(params.terminalInput()) } },
        Method.TerminalKey to { nothing { terminalOrFail().sendKey(params.read("event") { it.keyEvent() }) } },
        Method.TerminalPaste to { nothing { terminalOrFail().paste(params.string("text")) } },
        Method.TerminalResize to {
            val terminal = terminalOrFail()
            nothing {
                terminal.resize(
                    params.int("columns"),
                    params.int("rows"),
                    params.int("cellWidthPx"),
                    params.int("cellHeightPx"),
                )
            }
        },
        Method.TerminalScroll to { nothing { terminalOrFail().scroll(params.int("delta")) } },
        Method.TerminalClear to { nothing { terminalOrFail().clear() } },
        Method.TerminalResume to {
            nothing { state(TERMINALS)[params.long(TERMINAL)]?.resume(params.long("lastSeq")) }
        },
        Method.TerminalDestroy to { nothing { state(TERMINALS).stop(params.long(TERMINAL)) } },
    )

private fun Request.terminalOrFail(): ServerTerminal =
    checkNotNull(state(TERMINALS)[params.long(TERMINAL)]) { "This terminal has ended" }
