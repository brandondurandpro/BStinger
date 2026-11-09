package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.example.kotlineditor.model.LanguageServerChoice
import java.io.IOException

/** Language whose server the Settings dialog chooses. */
const val PHP_LANGUAGE = "php"

/** The categories of the Settings dialog, in the order of its list. */
enum class SettingsCategory(val title: String) {
    Appearance("Appearance"),
    Editor("Editor"),
    LanguageServers("Language Servers"),
    ProjectView("Project View"),
    Privacy("Privacy"),
    Experimental("Experimental"),
}

/** Every setting of the Settings dialog: its category, what it is called, and other words a search finds it by. */
enum class Setting(val category: SettingsCategory, val title: String, val description: String, keywords: String) {
    Theme(
        SettingsCategory.Appearance,
        "Theme",
        "The colors of the IDE and of the code: dark, light, or the one of the operating system.",
        "color scheme dark light night day mode appearance",
    ),
    FontSize(
        SettingsCategory.Editor,
        "Font size",
        "Size of the text of every editor (also Alt+Shift+. and Alt+Shift+,).",
        "zoom text",
    ),
    InlayHints(
        SettingsCategory.Editor,
        "Inlay hints",
        "The parameter names and the types the language server adds to the code.",
        "parameter names types variables lsp",
    ),
    PhpServer(
        SettingsCategory.LanguageServers,
        "PHP language server",
        "The server that understands PHP: completion, navigation, problems, inlay hints.",
        "lsp phpactor intelephense",
    ),
    AutoSelect(
        SettingsCategory.ProjectView,
        "Always select opened file",
        "The file shown in the editor is selected and revealed in the project tree.",
        "tree reveal",
    ),
    Telemetry(
        SettingsCategory.Privacy,
        "Share anonymous performance measures",
        "How long operations take, never a path, a name or a content (see docs/telemetry.md).",
        "telemetry statistics",
    ),
    AgentMode(
        SettingsCategory.Experimental,
        "Agent mode",
        "The IDE | Agent switch of the toolbar, where agents work in their own worktrees.",
        "ai agents conversations",
    ),
    AgentModeByDefault(
        SettingsCategory.Experimental,
        "Open in agent mode",
        "Agent mode is the default: the IDE reopens the last project in it at launch, without the welcome screen.",
        "default start launch startup ide welcome projects",
    ),
    Agent(
        SettingsCategory.Experimental,
        "Agent",
        "The agent of agent mode, from the ACP registry: the server installs it. Claude when none is chosen.",
        "acp registry claude codex gemini opencode goose",
    ),
    AgentCommand(
        SettingsCategory.Experimental,
        "Agent command",
        "A command line that starts any ACP agent instead (npx, a path…); empty to use the agent above.",
        "acp npx command line custom",
    ),
    ;

    private val searched = "$title $description $keywords ${category.title}".lowercase()

    /** Whether a search for [query] finds it: every word of the query is in its title, description or keywords. */
    fun matches(query: String): Boolean =
        query
            .lowercase()
            .split(' ')
            .filter { it.isNotBlank() }
            .all { it in searched }
}

/**
 * The Settings dialog (Ctrl+Alt+S, the project menu, the welcome screen): the category shown, the search, and the
 * language servers offered, asked to the server each time it opens. A change applies at once.
 */
@Stable
class SettingsState(private val languageServers: () -> LanguageServersState, parentScope: CoroutineScope) {
    private val scope = parentScope + SupervisorJob(parentScope.coroutineContext[Job])

    /** The category shown when there is no search. */
    var category by mutableStateOf(SettingsCategory.Editor)
        private set

    /** What the search field holds; not blank, it shows the matching settings of every category. */
    var query by mutableStateOf("")

    /** The PHP servers offered, null until the server answered. */
    var phpServers: LanguageServerChoice? by mutableStateOf(null)
        private set

    /** Why the servers offered could not be listed or changed, null when nothing failed. */
    var serverError: String? by mutableStateOf(null)
        private set

    /** The server being switched to, null when none. */
    var switchingTo: String? by mutableStateOf(null)
        private set

    /** The settings shown: those of [category], or those a non-blank [query] finds, in the order of the dialog. */
    val shown: List<Setting>
        get() {
            val searching = query.isNotBlank()
            return Setting.entries.filter { if (searching) it.matches(query) else it.category == category }
        }

    /** Shows [category], clearing the search. */
    fun select(category: SettingsCategory) {
        this.category = category
        query = ""
    }

    /** Asks the server which PHP servers it offers, and which one is used. */
    fun loadServers() {
        scope.launch {
            try {
                phpServers = languageServers().choiceFor(PHP_LANGUAGE)
                serverError = null
            } catch (failure: IOException) {
                serverError = "Could not list the language servers: ${failure.message}"
            }
        }
    }

    /** Uses [candidate] for PHP: the running PHP servers switch to it. */
    fun useServer(candidate: String) {
        if (switchingTo != null) return
        switchingTo = candidate
        scope.launch {
            try {
                languageServers().use(PHP_LANGUAGE, candidate)
                phpServers = phpServers?.copy(chosen = candidate)
                serverError = null
            } catch (failure: IOException) {
                serverError = "Could not change the language server: ${failure.message}"
            } finally {
                switchingTo = null
            }
        }
    }

    /** Stops what the dialog was waiting for. */
    fun close() = scope.cancel()
}
