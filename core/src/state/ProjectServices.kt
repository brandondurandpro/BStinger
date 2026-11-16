package org.example.kotlineditor.state

/**
 * The states the features keep in a project, each published under its [ServiceKey] and created on first use:
 * [ProjectHost.service] reaches them, [ProjectHost.provide] publishes them. Everything registered is taken
 * away when the project closes (the closing of the provider), so nothing outlives it.
 */
class ProjectServices {
    private val providers = LinkedHashMap<ServiceKey<*>, Lazy<Any?>>()

    /** Publishes the state built by [state] under [key]; the factory runs on the first [ProjectHost.service]. */
    fun <T : Any> provide(key: ServiceKey<T>, state: () -> T): AutoCloseable = provideOrNull(key) { state() }

    /**
     * Publishes the state built by [state] under [key], [state] may answer null: the key then stays without a
     * state, and [ProjectHost.serviceOrNull] answers null for it.
     */
    fun <T : Any> provideOrNull(key: ServiceKey<T>, state: () -> T?): AutoCloseable {
        val lazy = lazy(state)
        synchronized(providers) { providers[key] = lazy }
        return AutoCloseable {
            synchronized(providers) {
                if (providers[key] === lazy) providers.remove(key)
            }
        }
    }

    /** The state of [key], creating it if this is its first use. */
    operator fun <T : Any> get(key: ServiceKey<T>): T? {
        val lazy =
            synchronized(providers) { providers[key] }
                ?: return null
        @Suppress("UNCHECKED_CAST")
        return lazy.value as T?
    }
}
