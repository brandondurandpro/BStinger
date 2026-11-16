package org.example.kotlineditor.scratchpad.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.rpc.MethodSpec
import org.example.kotlineditor.rpc.PluginMethod
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.server.Handler
import org.example.kotlineditor.server.LocalServices
import org.example.kotlineditor.server.Request
import org.example.kotlineditor.server.ServerContribution
import org.example.kotlineditor.server.TextFiles
import org.example.kotlineditor.server.writeFileNow
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths

private const val PLUGIN_ID = "scratchpad"
private const val LOAD_NAME = "load"
private const val SAVE_NAME = "save"
private const val ROOT = "root"
private const val TEXT = "text"
private const val FILE_NAME = "scratchpad.md"

/** `plugin/scratchpad/load`: the notes of the project, or null when it holds none yet (not mutating). */
private val LOAD = PluginMethod(PLUGIN_ID, LOAD_NAME, mutating = false)

/** `plugin/scratchpad/save`: writes the notes of the project (mutating: it creates or replaces the file). */
private val SAVE = PluginMethod(PLUGIN_ID, SAVE_NAME, mutating = true)

/**
 * The server half of the bundled Scratchpad feature, found by the server through `META-INF/services`: it reads and
 * writes `scratchpad.md`, the notes file at the root of the project. Saving the same text twice writes the same
 * bytes twice, so a save repeated after a lost answer changes nothing.
 */
class ScratchpadServerContribution : ServerContribution {
    override fun handlers(services: LocalServices): Map<MethodSpec, Handler> {
        return mapOf(LOAD to { load() }, SAVE to { save() })
    }
}

private suspend fun Request.load(): JsonElement =
    withContext(Dispatchers.IO) {
        val notes = loadScratchpad(Paths.get(params.string(ROOT)))
        buildJsonObject { put(TEXT, notes) }
    }

private suspend fun Request.save(): JsonElement =
    withContext(Dispatchers.IO) {
        val root = Paths.get(params.string(ROOT))
        saveScratchpad(root, params.string(TEXT))
        JsonObject(emptyMap())
    }

/** The notes of [root], or null when the project holds none. Loading never creates the file. */
internal fun loadScratchpad(root: Path): String? {
    val notes = root.resolve(FILE_NAME)
    val exists = Files.exists(notes)
    if (!exists) return null
    return try {
        TextFiles.readText(notes)
    } catch (missing: NoSuchFileException) {
        null
    }
}

/** Writes [text] as the notes of [root]: plain UTF-8 for a new file, the file's charset for an existing one. */
internal fun saveScratchpad(root: Path, text: String) {
    val notes = root.resolve(FILE_NAME)
    val content = withFinalNewline(text)
    val existing = if (Files.exists(notes)) Files.readAllBytes(notes) else null
    writeFileNow(notes, TextFiles.encode(content, existing))
}

// A text file ends with a newline; the empty text stays empty.
private fun withFinalNewline(text: String): String =
    if (text.isEmpty() || text.endsWith('\n')) text else "$text\n"
