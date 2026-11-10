package org.example.kotlineditor.plugin.core

/** The version of the plugin API this IDE offers; a plugin built for another one does not load. */
const val API_VERSION = 1

/**
 * `plugin.yaml`: a plugin's identity and what it needs. Only a flat subset of YAML is read: `key: value` lines and
 * `key:` followed by `- item` lines, with `#` comments.
 */
class PluginManifest(
    /** Unique id; also the name of the plugin's folder. */
    val id: String,
    /** Name shown to the user. */
    val name: String,
    /** Version of the plugin. */
    val version: String,
    /** Version of the plugin API the plugin was built against. */
    val apiVersion: Int,
    /** Class of the client half, in `client.jar`. */
    val client: String?,
    /** Class of the server half, in `server.jar`; null for a plugin with a client half only. */
    val server: String?,
    /** Ids of the plugins this one needs loaded first. */
    val dependencies: List<String>,
    /** Declared permissions (`process`, `network`, `fs-write`): shown to the user, not enforced. */
    val permissions: List<String>,
) {
    companion object {
        /** Reads [text]; fails with a message naming the problem when the manifest is not valid. */
        fun parse(text: String): PluginManifest {
            val scalars = mutableMapOf<String, String>()
            val lists = mutableMapOf<String, MutableList<String>>()
            var listKey: String? = null
            for (raw in text.lines()) {
                val line = raw.substringBefore('#').trimEnd()
                if (line.isBlank()) continue
                val item = line.trimStart().takeIf { it.startsWith("- ") }
                if (item != null) {
                    val key = requireNotNull(listKey) { "A list item without a key: $line" }
                    lists.getValue(key) += item.removePrefix("- ").trim().unquote()
                    continue
                }
                val key = line.substringBefore(':').trim()
                val value = line.substringAfter(':', "").trim().unquote()
                require(':' in line && key.isNotEmpty()) { "Not a key: $line" }
                if (value.isEmpty()) {
                    listKey = key
                    lists[key] = mutableListOf()
                } else {
                    listKey = null
                    scalars[key] = value
                }
            }
            return build(scalars, lists)
        }

        private fun build(scalars: Map<String, String>, lists: Map<String, List<String>>): PluginManifest {
            val id = requireNotNull(scalars["id"]) { "The manifest has no id" }
            require(ID.matches(id)) { "Invalid plugin id: $id" }
            val api = requireNotNull(scalars["apiVersion"]?.toIntOrNull()) { "The manifest has no valid apiVersion" }
            return PluginManifest(
                id = id,
                name = scalars["name"] ?: id,
                version = requireNotNull(scalars["version"]) { "The manifest has no version" },
                apiVersion = api,
                client = scalars["client"],
                server = scalars["server"],
                dependencies = lists["dependencies"].orEmpty(),
                permissions = lists["permissions"].orEmpty(),
            )
        }

        private val ID = Regex("[a-z][a-z0-9-]*")

        private fun String.unquote() = removeSurrounding("\"").removeSurrounding("'")
    }
}
