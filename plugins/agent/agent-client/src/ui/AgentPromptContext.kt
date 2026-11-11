package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.AgentAttachment
import org.example.kotlineditor.state.Completing
import org.example.kotlineditor.state.FileIndex
import org.example.kotlineditor.state.PromptContext

// What the prompt shows of the context joined to it, and how the user joins it: an `@` completed from the files of
// the project, an image pasted.

private const val FILE_SUGGESTIONS = 50

/** The files matching the word after an `@`, searched off the UI thread for the word they were found for. */
@Stable
internal class FileSuggestions {
    private var foundFor: String? by mutableStateOf(null)
    private var found: List<Suggestion> by mutableStateOf(emptyList())

    /** The files found for [word]; none while they are searched. */
    fun of(word: Completing.File): List<Suggestion> = if (foundFor == word.query) found else emptyList()

    /** Searches [index] for [word]; picking a file joins it to [context] and removes the word. */
    suspend fun search(index: FileIndex, word: Completing.File, context: PromptContext) {
        index.ensureLoaded()
        val matches = index.searchFiles(word.query, FILE_SUGGESTIONS)
        found =
            matches.map { match ->
                val path = match.relativePath
                val directory = path.substringBeforeLast('/', "")
                Suggestion(path, directory, replacement = "") { context.attach(AgentAttachment.File(path)) }
            }
        foundFor = word.query
    }
}

/** Searches the files while the prompt completes an `@`: again when the word changes, or once the index is built. */
@Composable
internal fun SearchesFiles(
    suggestions: FileSuggestions,
    word: Completing.File?,
    index: FileIndex,
    context: PromptContext,
) {
    val indexed = index.files
    LaunchedEffect(word?.query, indexed) {
        if (word != null) suggestions.search(index, word, context)
    }
}

/** Ctrl+V (Cmd+V) in the prompt while the clipboard holds an image: the image joins the prompt. */
internal fun pastesImage(event: KeyEvent, context: PromptContext): Boolean {
    val paste = event.key == Key.V && (event.isCtrlPressed || event.isMetaPressed)
    if (!paste || event.type != KeyEventType.KeyDown) return false
    val png = TextClipboard.image() ?: return false
    context.paste(png)
    return true
}

/** What is joined to the prompt, each with a button that removes it; nothing when there is none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContextChips(context: PromptContext, modifier: Modifier = Modifier) {
    val attachments = context.attachments.toList()
    if (attachments.isEmpty()) return
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        attachments.forEach { attachment ->
            if (attachment is AgentAttachment.Image) {
                ImageChip(attachment) { context.remove(attachment) }
                return@forEach
            }
            ContextChip(attachment.label, Modifier.testTag(AgentTags.agentAttachment(attachment.label))) {
                CloseIcon(
                    Modifier
                        .padding(start = 6.dp)
                        .clickable { context.remove(attachment) }
                        .size(14.dp)
                        .testTag(AgentTags.agentAttachmentRemove(attachment.label)),
                )
            }
        }
    }
}

/** What was joined to a prompt sent, under its text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SentContext(labels: List<String>, modifier: Modifier = Modifier) {
    if (labels.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
        labels.forEach { label -> ContextChip(label, Modifier.testTag(AgentTags.agentSentAttachment(label))) }
    }
}

@Composable
private fun ContextChip(label: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier
            .background(IdeColors.Panel, IdeShapes.Medium)
            .border(1.dp, IdeColors.Separator, IdeShapes.Medium)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label("@$label", color = IdeColors.Text, fontSize = 12.sp)
        trailing()
    }
}
