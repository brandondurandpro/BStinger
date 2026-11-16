package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.PluginServer
import org.example.kotlineditor.rpc.PluginMethod
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.rpc.stringOrNull
import java.io.IOException

private const val PLUGIN_ID = "scratchpad"
private const val ROOT_PARAM = "root"
private const val TEXT_PARAM = "text"
private const val TEXT = "text"
private const val DEFAULT_ERROR = "The scratchpad failed"

/** `plugin/scratchpad/load`: the notes of the project, or null when it holds none yet (not mutating). */
internal val LOAD_METHOD = PluginMethod(PLUGIN_ID, "load", mutating = false)

/** `plugin/scratchpad/save`: writes the notes of the project (mutating: it creates or replaces the file). */
internal val SAVE_METHOD = PluginMethod(PLUGIN_ID, "save", mutating = true)

/**
 * The notes file of a project (`scratchpad.md`), read and written by the server half of the plugin. The field is
 * edited in place and [save] writes it; [unsaved] says when the field holds what the file does not yet. A failed
 * call (server unreachable) keeps the notes and explains why in [error], so the panel never empties because the
 * link went down.
 */
@Stable
class ScratchpadState(
    /** The project the notes belong to, for the root its server half reads and writes them at. */
    private val root: FileEntry,
    /** The server the plugin's methods are called on. */
    private val pluginServer: PluginServer,
) {
    /** What the file holds, as the last load or save saw it ("" when the project holds none). */
    var saved: String by mutableStateOf("")
        private set

    /** What the field holds. */
    var draft: String by mutableStateOf("")
        private set

    /** True while the notes are read, so the panel shows it is working. */
    var loading by mutableStateOf(false)
        private set

    /** True while a save is in flight, so the button does not start a second one. */
    var saving by mutableStateOf(false)
        private set

    /** Why the last load or save failed; null when it did not. */
    var error: String? by mutableStateOf(null)
        private set

    /** How many saves answered, since the state exists; the tests await it to know a save ended. */
    var saves by mutableStateOf(0)
        private set

    /** True when the field holds what the file does not yet. */
    val unsaved: Boolean get() = draft != saved

    /** Reads the notes of the project; a project that holds none starts with an empty field. */
    suspend fun load() {
        loading = true
        try {
            val answer = pluginServer.call(LOAD_METHOD, loadParams())
            val notes = answer.jsonObject.stringOrNull(TEXT)
            saved = notes ?: ""
            draft = saved
            error = null
        } catch (broken: IOException) {
            error = broken.message ?: DEFAULT_ERROR
        } finally {
            loading = false
        }
    }

    /** Writes the field as the notes of the project; the field keeps the newline the file now ends with. */
    suspend fun save() {
        val content = withFinalNewline(draft)
        draft = content
        saving = true
        try {
            pluginServer.call(SAVE_METHOD, saveParams(content))
            saved = content
            saves += 1
            error = null
        } catch (broken: IOException) {
            error = broken.message ?: DEFAULT_ERROR
        } finally {
            saving = false
        }
    }

    /** Edits the field, as the panel types into it. */
    fun edit(text: String) {
        draft = text
    }

    private fun loadParams(): JsonObject = buildJsonObject { put(ROOT_PARAM, root.path) }

    private fun saveParams(content: String): JsonObject =
        buildJsonObject {
            put(ROOT_PARAM, root.path)
            put(TEXT_PARAM, content)
        }
}

/** The Scratchpad state of a project (its notes), as [ProjectHost.service] gives it to the Scratchpad UI. */
val SCRATCHPAD_SERVICE = ServiceKey<ScratchpadState>("scratchpad")

/**
 * The Scratchpad state of [host], created on the first use: the plugin publishes its state itself, since the project
 * provides the states of the features built into it, not the ones of the plugins.
 */
fun ProjectHost.scratchpadState(): ScratchpadState {
    val existing = serviceOrNull(SCRATCHPAD_SERVICE)
    if (existing != null) return existing
    provide(SCRATCHPAD_SERVICE) { ScratchpadState(root, pluginServer) }
    return service(SCRATCHPAD_SERVICE)
}

// What a save writes: a text file ends with a newline; the empty text stays empty.
private fun withFinalNewline(text: String): String =
    if (text.isEmpty() || text.endsWith('\n')) text else "$text\n"
