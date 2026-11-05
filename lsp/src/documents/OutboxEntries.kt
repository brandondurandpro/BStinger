package org.example.kotlineditor.lsp.documents

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.model.TextChange

private const val TEXT_DOCUMENT = "textDocument"
private const val URI = "uri"
private const val VERSION = "version"
private const val TEXT = "text"
private const val LINE = "line"
private const val CHARACTER = "character"

// About what a change adds to a message besides its text: its range and the JSON around it.
private const val CHANGE_OVERHEAD = 64L

// About what a JSON member or element adds besides its content: quotes, colon, comma.
private const val JSON_OVERHEAD = 4L

/** What a [DocumentOutbox] holds until its coroutine writes it to the server. */
internal sealed interface Outgoing {
    /** About how many bytes it keeps, and writes. */
    val bytes: Long

    /** A notification to write. */
    sealed interface Message : Outgoing {
        /** Its LSP method. */
        val method: String

        /** Its parameters, built when it is written. */
        fun params(): JsonObject
    }

    /** A notification whose parameters are built already: small ones, and those of the workspace. */
    class Notification(override val method: String, private val parameters: JsonObject) : Message {
        override val bytes = jsonSize(parameters)

        override fun params(): JsonObject = parameters
    }

    /** `didOpen` of a document: [lines] are joined into its text only when it is written. */
    class Opening(
        private val uri: String,
        private val language: String,
        private val version: Int,
        private val lines: List<String>,
    ) : Message {
        override val method = "textDocument/didOpen"
        override val bytes = textSize(lines)

        override fun params(): JsonObject =
            buildJsonObject {
                putJsonObject(TEXT_DOCUMENT) {
                    put(URI, uri)
                    put("languageId", language)
                    put(VERSION, version)
                    put(TEXT, lines.joinToString("\n"))
                }
            }
    }

    /**
     * `didChange` of the document at [uri]: the changes made since the last one written, merged into one message.
     * Either the whole text ([lines], joined only when it is written), or the edits in order.
     */
    class Changing(val uri: String) : Message {
        override val method = "textDocument/didChange"
        var version = 0
        private var lines: List<String>? = null
        private val changes = ArrayList<TextChange>()

        override var bytes = 0L
            private set

        /** Whether it carries the whole text, which later changes replace. */
        val isWhole: Boolean get() = lines != null

        /** Replaces what it carries with the whole text, [snapshot]. */
        fun replaceText(snapshot: List<String>) {
            lines = snapshot
            changes.clear()
            bytes = textSize(snapshot)
        }

        /** Adds [more] edits after those it carries. */
        fun add(more: List<TextChange>) {
            changes += more
            bytes += changesSize(more)
        }

        override fun params(): JsonObject {
            val whole = lines
            val contentChanges =
                if (whole != null) {
                    listOf(buildJsonObject { put(TEXT, whole.joinToString("\n")) })
                } else {
                    changes.map(::contentChange)
                }
            return buildJsonObject {
                putJsonObject(TEXT_DOCUMENT) {
                    put(URI, uri)
                    put(VERSION, version)
                }
                put("contentChanges", JsonArray(contentChanges))
            }
        }
    }

    /** Completes [done] once everything queued before it is written. */
    class Flush(val done: CompletableDeferred<Unit>) : Outgoing {
        override val bytes = 0L
    }
}

/** The text of [document] as it is now, to be joined later: its lines are immutable strings, shared with it. */
internal fun snapshotOf(document: MirroredDocument): List<String> = ArrayList(document.lines)

/** The parameters of the notifications that only name a document (`didClose`, `didSave`). */
internal fun identifierOf(document: MirroredDocument): JsonObject =
    buildJsonObject { putJsonObject(TEXT_DOCUMENT) { put(URI, document.uri) } }

/** The bytes [changes] take in a message, about. */
internal fun changesSize(changes: List<TextChange>): Long = changes.sumOf { it.text.length + CHANGE_OVERHEAD }

private fun textSize(lines: List<String>): Long {
    val characters = lines.sumOf { it.length.toLong() }
    return characters + lines.size
}

private fun jsonSize(element: JsonElement): Long =
    when (element) {
        is JsonObject -> element.entries.sumOf { (key, value) -> key.length + jsonSize(value) + JSON_OVERHEAD }
        is JsonArray -> element.sumOf { jsonSize(it) + JSON_OVERHEAD }
        is JsonPrimitive -> element.content.length + JSON_OVERHEAD
    }

private fun contentChange(change: TextChange) =
    buildJsonObject {
        putJsonObject("range") {
            putJsonObject("start") {
                put(LINE, change.startLine)
                put(CHARACTER, change.startColumn)
            }
            putJsonObject("end") {
                put(LINE, change.endLine)
                put(CHARACTER, change.endColumn)
            }
        }
        put(TEXT, change.text)
    }
