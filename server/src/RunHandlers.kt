package org.example.kotlineditor.server

import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.long
import org.example.kotlineditor.rpc.read
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.rpc.strings

private const val DIRECTORY = "directory"
private const val STREAM = "stream"
private const val LAST_SEQ = "lastSeq"

/** Commands run in a project (the Run tool window): start, stop, and replay after a reconnection. */
internal fun runHandlers(): Map<Method, Handler> =
    mapOf(
        Method.RunStart to {
            val directory = params.string(DIRECTORY)
            val command = params.read("command") { it.strings() }
            nothing { runs.start(id, directory, command) }
        },
        Method.RunStop to { nothing { runs.stop(params.long(STREAM)) } },
        Method.RunResume to { nothing { runs.resume(params.long(STREAM), params.long(LAST_SEQ)) } },
    )
