package org.example.kotlineditor.server

import org.example.kotlineditor.rpc.MethodSpec

/**
 * What a feature (a built-in one, later a plugin) adds to the server: the handlers of its methods. Nothing else
 * assembles the handlers, so a feature is added or removed by the list it is in.
 */
fun interface ServerContribution {
    /** The handler of each method of the feature. */
    fun handlers(services: LocalServices): Map<MethodSpec, Handler>
}

/**
 * The built-in features, in the order of the protocol, then the bundled plugins that declare a [ServerContribution]
 * service (`META-INF/services`), which are linked statically but add their handlers through the same list.
 */
internal fun builtInContributions(): List<ServerContribution> = coreContributions() + bundledContributions()

private fun bundledContributions(): List<ServerContribution> {
    val loader = ServerContribution::class.java.classLoader
    val services = java.util.ServiceLoader.load(ServerContribution::class.java, loader)
    return services.toList()
}

private fun coreContributions(): List<ServerContribution> =
    listOf(
        ServerContribution(::fileHandlers),
        ServerContribution(::deployHandlers),
        ServerContribution(::searchHandlers),
        ServerContribution { gitHandlers(it.git) },
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
