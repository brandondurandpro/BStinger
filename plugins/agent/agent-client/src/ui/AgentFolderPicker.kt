package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.FolderListing
import org.example.kotlineditor.state.browseFolder
import org.example.kotlineditor.state.chooseFolder
import org.example.kotlineditor.state.fieldFor
import org.example.kotlineditor.state.folderLabel
import org.example.kotlineditor.state.listFolders
import org.example.kotlineditor.state.projectName
import org.example.kotlineditor.state.folderNameOf
import org.example.kotlineditor.state.relativeFolder
import org.example.kotlineditor.state.resolveFolder
import org.example.kotlineditor.state.rootPath
import org.example.kotlineditor.state.separatorOf

private val MENU_WIDTH = 380.dp

// A menu shows a few folders at once, the rest comes scrolling in place.
private val MENU_HEIGHT = 240.dp

// Every row of the list has the same height: the menu asks for the height of its content, which a lazy list can only
// answer when it has a size of its own.
private val ROW_HEIGHT = 30.dp

// What is typed is listed once the user paused: a key does not read a folder of the server.
private const val LISTING_DELAY_MS = 120L

internal const val NO_PROJECT = "No project"
private const val LOCKED_REASON = "The folder is fixed once the conversation has started"

/**
 * The folder the agent of [conversation] works in, in the prompt bar after the team: "Project root", or the folder
 * under it. Its menu has a field with the path (absolute, or under the folder it works in), the folders it leads to,
 * the root, and the Open Project dialog to browse the disk; a folder elsewhere is where the conversation then works,
 * in this window. From the first prompt on it only shows it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderPicker(agent: AgentState, conversation: Conversation) {
    var open by remember { mutableStateOf(false) }
    val locked = conversation.started || conversation.busy
    Box {
        TooltipArea(tooltip = { Tooltip(if (locked) LOCKED_REASON else "Working folder") }) {
            val label = if (conversation.scratch) NO_PROJECT else folderLabel(conversation.subdirectory)
            PromptBarButton(label, open, locked, AgentTags.AGENT_FOLDER) { open = true }
        }
        val menuModifier = Modifier.widthIn(max = MENU_WIDTH).closesOnEscape { open = false }
        IdeDropdownMenu(open && !locked, onDismissRequest = { open = false }, menuModifier) {
            FolderMenu(agent, conversation) { open = false }
        }
    }
}

@Composable
private fun FolderMenu(agent: AgentState, conversation: Conversation, close: () -> Unit) {
    // The root of the folder the conversation works in: the project, or the folder chosen for it.
    val root = conversation.rootPath(agent.project)
    var typed by remember { mutableStateOf(fieldFor(root, conversation.subdirectory)) }
    var listing by remember { mutableStateOf<FolderListing?>(null) }
    val scope = rememberCoroutineScope()
    // The last thing typed is what is listed: an answer for an earlier text is cancelled with its effect.
    LaunchedEffect(typed) {
        delay(LISTING_DELAY_MS)
        listing = agent.listFolders(conversation, typed)
    }
    val choose = { text: String ->
        scope.launch {
            agent.chooseFolder(conversation, text).join()
            if (conversation.folderError == null) close()
        }
        Unit
    }
    Column(Modifier.width(MENU_WIDTH).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AgentFilterField(typed, AgentTags.AGENT_FOLDER_PATH, "Folder…", onSubmit = { choose(typed) }) {
            typed = it
            conversation.folderError = null
        }
        val failure = conversation.folderError ?: listing?.failure
        if (failure != null) {
            Label(failure, Modifier.testTag(AgentTags.AGENT_FOLDER_ERROR), color = IdeColors.Error, fontSize = 12.sp, maxLines = 3)
        }
        if (conversation.scratch) {
            ProjectChoices(agent) { path -> choose(path) }
        } else {
            IdeMenuItem(
                text = { Label("Project root") },
                onClick = { choose("") },
                modifier = Modifier.testTag(AgentTags.AGENT_FOLDER_ROOT),
            )
        }
        FolderList(listing?.folders.orEmpty()) { path -> typed = intoField(root, path) }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (agent.project.canStartInOtherProject) {
                IdeButton("Browse…", { close(); agent.browseFolder(conversation) }, Modifier.testTag(AgentTags.AGENT_FOLDER_BROWSE))
            }
            IdeButton("Choose", { choose(typed) }, Modifier.testTag(AgentTags.AGENT_FOLDER_APPLY), primary = true)
        }
    }
}

// The projects a conversation without one can work in: the project of this window, then the recent ones.
@Composable
private fun ProjectChoices(agent: AgentState, choose: (String) -> Unit) {
    val own = agent.project.root.path
    IdeMenuItem(
        text = { Label("${agent.projectName} (this project)") },
        onClick = { choose(own) },
        modifier = Modifier.testTag(AgentTags.AGENT_FOLDER_THIS_PROJECT),
    )
    for (path in agent.project.otherProjects.filter { it != own }) {
        IdeMenuItem(
            text = { Label(folderNameOf(path)) },
            onClick = { choose(path) },
            modifier = Modifier.testTag(AgentTags.agentFolderProject(path)),
        )
    }
}

// What the field says once the folder [path] is entered: under the project its relative path, else the absolute one.
private fun intoField(root: String, path: String): String {
    val relative = relativeFolder(root, path)
    if (relative != null) return fieldFor(root, relative)
    val separator = separatorOf(root)
    return path.trimEnd('/', '\\') + separator
}

// The folders as a lazy list (thousands of them compose only the rows shown); a click goes into one.
@Composable
private fun FolderList(folders: List<FileEntry>, into: (String) -> Unit) {
    if (folders.isEmpty()) return
    // A fixed size: the menu measures the intrinsic size of its content, which a lazy list cannot answer.
    val height = minOf(MENU_HEIGHT, ROW_HEIGHT * folders.size)
    LazyColumn(Modifier.width(MENU_WIDTH).height(height).testTag(AgentTags.AGENT_FOLDER_LIST)) {
        items(folders, key = { it.path }) { folder ->
            IdeMenuItem(
                text = { Label(folder.name, maxLines = 1) },
                onClick = { into(folder.path) },
                modifier = Modifier.height(ROW_HEIGHT).testTag(AgentTags.agentFolderChoice(folder.path)),
            )
        }
    }
}
