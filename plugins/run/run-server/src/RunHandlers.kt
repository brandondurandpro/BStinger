package org.example.kotlineditor.run.server

import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.MethodSpec
import org.example.kotlineditor.rpc.long
import org.example.kotlineditor.rpc.read
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.rpc.strings
import org.example.kotlineditor.server.Handler
import org.example.kotlineditor.server.LocalServices
import org.example.kotlineditor.server.ServerContribution
import org.example.kotlineditor.server.SessionKey
import org.example.kotlineditor.server.nothing

private const val DIRECTORY = "directory"
private const val STREAM = "stream"
private const val LAST_SEQ = "lastSeq"

private val RUNS = SessionKey("run") { RunRegistry(it.push) }

/** Found by the server through `META-INF/services`. */
class RunServerContribution : ServerContribution {
    override fun handlers(services: LocalServices) = runHandlers(services)
}

/** Commands run in a project (the Run tool window): start, stop, and replay after a reconnection. */
internal fun runHandlers(services: LocalServices): Map<MethodSpec, Handler> =
    mapOf(
        Method.RunManifests to { findRunManifests(services, params.string("root")).manifestsJson() },
        Method.RunStart to {
            val directory = params.string(DIRECTORY)
            val command = params.read("command") { it.strings() }
            nothing { state(RUNS).start(id, directory, command) }
        },
        Method.RunStop to { nothing { state(RUNS).stop(params.long(STREAM)) } },
        Method.RunResume to { nothing { state(RUNS).resume(params.long(STREAM), params.long(LAST_SEQ)) } },
    )
