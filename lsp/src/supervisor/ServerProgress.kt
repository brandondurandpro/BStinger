package org.example.kotlineditor.lsp.supervisor

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.ConcurrentHashMap

/** A work in progress the server reported (`$/progress`): indexing, mostly. */
internal class Progress(val title: String?, val percent: Int?)

/** The works in progress of the current process, by token. */
internal class ServerProgress {
    private val progress = ConcurrentHashMap<String, Progress>()

    /** Whether no work is in progress. */
    val isEmpty: Boolean get() = progress.isEmpty()

    /** The work shown in the status bar, null when none. */
    fun current(): Progress? = progress.values.firstOrNull()

    /** Forgets every work: the process stopped or another one started. */
    fun clear() = progress.clear()

    /** Applies the `$/progress` notification [fields]; false when it names no token or value, and is ignored. */
    fun update(fields: JsonObject): Boolean {
        val token = (fields["token"] as? JsonPrimitive)?.contentOrNull
        val value = fields["value"] as? JsonObject
        if (token == null || value == null) return false
        val percent = (value["percentage"] as? JsonPrimitive)?.intOrNull
        when ((value["kind"] as? JsonPrimitive)?.contentOrNull) {
            "begin" -> progress[token] = Progress((value["title"] as? JsonPrimitive)?.contentOrNull, percent)
            "report" -> progress[token]?.let { progress[token] = Progress(it.title, percent ?: it.percent) }
            "end" -> progress.remove(token)
        }
        return true
    }
}
