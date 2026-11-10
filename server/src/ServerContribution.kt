package org.example.kotlineditor.server

import org.example.kotlineditor.rpc.MethodSpec

/**
 * What a feature (a built-in one, later a plugin) adds to the server: the handlers of its methods. Nothing else
 * assembles the handlers, so a feature is added or removed by the list it is in.
 */
internal fun interface ServerContribution {
    /** The handler of each method of the feature. */
    fun handlers(services: LocalServices): Map<MethodSpec, Handler>
}

/** The built-in features, in the order of the protocol. */
internal fun builtInContributions(): List<ServerContribution> =
    listOf(
        ServerContribution(::fileHandlers),
        ServerContribution(::deployHandlers),
        ServerContribution(::searchHandlers),
        ServerContribution { gitHandlers(it.git) },
        ServerContribution { terminalHandlers() },
        ServerContribution { runHandlers() },
        ServerContribution(::languageServerHandlers),
        ServerContribution(::agentHandlers),
    )

/**
 * Implementation of each [Method] and of the methods of [extra] contributions: read the named params, call the local
 * services, return the JSON result. A method handled twice is a bug of a contribution and fails at once.
 */
internal fun requestHandlers(
    services: LocalServices,
    extra: List<ServerContribution> = emptyList(),
): Map<MethodSpec, Handler> {
    val handlers = LinkedHashMap<MethodSpec, Handler>()
    for (contribution in builtInContributions() + extra) {
        for ((method, handler) in contribution.handlers(services)) {
            require(handlers.put(method, handler) == null) { "${method.rpcName} has two handlers" }
        }
    }
    return handlers
}
