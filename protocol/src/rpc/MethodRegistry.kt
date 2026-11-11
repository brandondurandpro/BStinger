package org.example.kotlineditor.rpc

import java.util.concurrent.ConcurrentHashMap

/** A method declared by a plugin; its [rpcName] is `plugin/<pluginId>/<name>`. */
class PluginMethod(
    /** Id of the plugin that declares the method. */
    val pluginId: String,
    name: String,
    override val mutating: Boolean,
) : MethodSpec {
    override val rpcName: String = "$PLUGIN_PREFIX$pluginId/$name"

    // Two declarations of the same name are the same method: a client and a plugin's jar each make their own.
    override fun equals(other: Any?): Boolean = other is PluginMethod && other.rpcName == rpcName

    override fun hashCode(): Int = rpcName.hashCode()

    override fun toString(): String = rpcName
}

private const val PLUGIN_PREFIX = "plugin/"

/**
 * The methods the codec can name: the built-in [Method]s and those of the loaded plugins. A name that is not here is
 * an unknown method, answered with `METHOD_NOT_FOUND`.
 */
object MethodRegistry {
    private val builtIn: Map<String, MethodSpec> = Method.entries.associateBy { it.rpcName }
    private val plugins = ConcurrentHashMap<String, MethodSpec>()

    /** The method named [rpcName], or null when none is registered. */
    fun resolve(rpcName: String): MethodSpec? = builtIn[rpcName] ?: plugins[rpcName]

    /** Registers [methods]; returns what undoes it (a plugin unloading). A name already taken is refused. */
    fun register(methods: Collection<PluginMethod>): AutoCloseable {
        val added = mutableListOf<String>()
        try {
            for (method in methods) {
                val name = method.rpcName
                require(name !in builtIn) { "$name is a built-in method" }
                val previous = plugins.putIfAbsent(name, method)
                require(previous == null) { "$name is already registered" }
                added += name
            }
        } catch (failure: IllegalArgumentException) {
            added.forEach(plugins::remove)
            throw failure
        }
        return AutoCloseable { added.forEach(plugins::remove) }
    }
}
