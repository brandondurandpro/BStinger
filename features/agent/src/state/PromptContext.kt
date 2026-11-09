package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import org.example.kotlineditor.model.AgentAttachment
import java.util.Base64

// What the user joins to a prompt: files mentioned with `@`, lines of the editor (Ctrl+Alt+K, "Send to the agent"),
// images pasted. The prompt shows them above the text, each one removable, until it is sent.

/** A prompt on its way to the server: its item, its text and what goes with it. */
internal data class OutgoingPrompt(val itemId: String, val text: String, val attachments: List<AgentAttachment>)

/** What the user joins to the next prompt of a conversation. */
@Stable
class PromptContext {
    /** In the order joined, each once. */
    val attachments = mutableStateListOf<AgentAttachment>()

    /** Grows when something asks for the prompt to take the focus: lines just sent to it. */
    var focusRequests by mutableIntStateOf(0)
        private set
    private var pasted = 0

    /** Joins [attachment] to the prompt, unless it already is. */
    fun attach(attachment: AgentAttachment) {
        if (attachment !in attachments) attachments.add(attachment)
    }

    fun remove(attachment: AgentAttachment) {
        attachments.remove(attachment)
    }

    /** Joins the image [png] pasted in the prompt. */
    fun paste(png: ByteArray) {
        pasted++
        val data = Base64.getEncoder().encodeToString(png)
        attach(AgentAttachment.Image("image-$pasted.png", "image/png", data))
    }

    /** Asks the prompt to take the focus. */
    fun focus() {
        focusRequests++
    }

    /** What goes with the prompt being sent; the next one starts with nothing. */
    internal fun take(): List<AgentAttachment> {
        val taken = attachments.toList()
        attachments.clear()
        return taken
    }
}

/**
 * What [tab] sends to the agent: the lines selected (whole), else the file; null while it loads, and for a diff, whose
 * text is not a file's.
 */
fun attachmentOf(tab: EditorTab): AgentAttachment? {
    val document = tab.document
    if (tab.isDiff || document == null) return null
    val selection = document.selectionRange() ?: return AgentAttachment.File(tab.relativePath)
    val (start, end) = selection
    // A selection that ends at the start of a line does not take that line.
    val endsBefore = end.column == 0 && end.line > start.line
    val last = if (endsBefore) end.line - 1 else end.line
    val text = document.lines.subList(start.line, last + 1).joinToString("\n")
    return AgentAttachment.Lines(tab.relativePath, start.line + 1, last + 1, text)
}

/**
 * Joins [attachment] to the prompt of the conversation shown (a new one when there is none), which shows its prompt
 * and gives it the focus.
 */
fun AgentState.attachToPrompt(attachment: AgentAttachment) {
    val conversation = current ?: newConversation()
    conversation.context.attach(attachment)
    conversation.showsChat = true
    conversation.context.focus()
}

private val DROPPED_IMAGES =
    mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp")

private val DROPPED_SOUNDS = mapOf("wav" to "audio/wav", "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "m4a" to "audio/mp4")

/**
 * Joins a file dropped on the prompt: a file of the directory [root] the agent works in as such, an image from
 * elsewhere as an image; anything else cannot be read by the agent, and false is returned.
 */
fun PromptContext.drop(file: java.io.File, root: String): Boolean {
    val base = java.io.File(root).canonicalFile
    val dropped = file.canonicalFile
    val inside = dropped.startsWith(base) && dropped.isFile
    val mime = DROPPED_IMAGES[dropped.extension.lowercase()]
    val sound = DROPPED_SOUNDS[dropped.extension.lowercase()]
    return when {
        inside -> {
            attach(AgentAttachment.File(dropped.relativeTo(base).path.replace('\\', '/')))
            true
        }

        sound != null && dropped.isFile -> {
            val data = java.util.Base64.getEncoder().encodeToString(dropped.readBytes())
            attach(AgentAttachment.Audio(dropped.name, sound, data))
            true
        }

        mime != null && dropped.isFile -> {
            val data = java.util.Base64.getEncoder().encodeToString(dropped.readBytes())
            attach(AgentAttachment.Image(dropped.name, mime, data))
            true
        }

        else -> false
    }
}
