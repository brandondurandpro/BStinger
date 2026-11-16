package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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

/** The languages whose server the Settings dialog chooses, in the order it lists them. */
val SERVER_LANGUAGES = listOf("php", "typescript", "vue", "tailwind")

/** The categories that are the IDE's only, which the dialog of the agent window does not list. */
val IDE_ONLY_CATEGORIES: Set<SettingsCategory> =
    setOf(SettingsCategory.LanguageServers, SettingsCategory.RemoteDevelopment, SettingsCategory.Plugins)

/** The categories of the Settings dialog, in the order of its list. */
enum class SettingsCategory(val title: String) {
    Appearance("Appearance"),
    Editor("Editor"),
    LanguageServers("Language Servers"),
    ProjectView("Project View"),
    Privacy("Privacy"),
    RemoteDevelopment("Remote Development"),
    CommandLine("Command Line"),
    Plugins("Plugins"),
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
    LanguageServers(
        SettingsCategory.LanguageServers,
        "Language servers",
        "The servers that understand each language: completion, navigation, problems, inlay hints.",
        "lsp php phpactor intelephense typescript javascript react tsx jsx tsserver",
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
    RemoteServer(
        SettingsCategory.RemoteDevelopment,
        "Server on remote hosts",
        "Where the server installed on a host comes from: a local bundle, or the GitHub release of this version.",
        "ssh bundle download github release install remote",
    ),
    CommandLine(
        SettingsCategory.CommandLine,
        "Shell command ide",
        "Opens the IDE on a folder from a terminal: ide to open the current directory, ide <folder> for another one.",
        "terminal shell bash powershell cmd path launcher open folder directory cli",
    ),
    Plugins(
        SettingsCategory.Plugins,
        "Installed plugins",
        "The plugins of the IDE: install one from a file, turn it on or off, or update it. No restart.",
        "extensions install enable disable update add-on",
    ),
    AgentMode(
        SettingsCategory.Experimental,
        "Agent window",
        "The Agent button of the toolbar, which opens the agent in a window of its own, beside the IDE one.",
        "ai agents conversations",
    ),
    AgentModeByDefault(
        SettingsCategory.Experimental,
        "Open the agent window at launch",
        "The agent window is the default: the IDE reopens the last project with it at launch, with no welcome screen.",
        "default start launch startup ide welcome projects",
    ),
    Agent(
        SettingsCategory.Experimental,
        "Agent",
        "The agent of the agent window, from the ACP registry: the server installs it. Claude when none is chosen.",
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
 *
 * @param languageServers The servers of the server shown, asked when the dialog opens.
 * @param parentScope The scope cancelled when the IDE leaves its server.
 * @param hidden The categories the dialog does not list: the agent window's dialog hides the IDE's.
 */
@Stable
class SettingsState(
    private val languageServers: () -> LanguageServersState,
    parentScope: CoroutineScope,
    private val hidden: Set<SettingsCategory> = emptySet(),
) {
    private val scope = parentScope + SupervisorJob(parentScope.coroutineContext[Job])

    /** The category shown when there is no search. */
    var category by mutableStateOf(SettingsCategory.Editor)
        private set

    /** What the search field holds; not blank, it shows the matching settings of every category. */
    var query by mutableStateOf("")

    private val choices = mutableStateMapOf<String, LanguageServerChoice>()

    // The connection the servers were listed on: asking costs the server a check of each runtime, once is enough.
    private var listedOn: Long? = null

    /** Opens a web address (a server's GitHub page) in the browser of the IDE; the project's window replaces it. */
    var openUrl: (String) -> Unit = ::openInBrowser

    /** Whether the server answered for every language. */
    val loaded: Boolean get() = SERVER_LANGUAGES.all { choices[it] != null }

    /** Why the servers offered could not be listed or changed, null when nothing failed. */
    var serverError: String? by mutableStateOf(null)
        private set

    /** The server being switched to, null when none. */
    var switchingTo: String? by mutableStateOf(null)
        private set

    /** The categories the dialog lists, in order: every one, but the hidden ones. */
    val categories: List<SettingsCategory> get() = SettingsCategory.entries.filter { it !in hidden }

    /** The settings shown: those of [category], or those a non-blank [query] finds, in the order of the dialog. */
    val shown: List<Setting>
        get() {
            val searching = query.isNotBlank()
            return Setting.entries.filter {
                when {
                    it.category in hidden -> false
                    searching -> it.matches(query)
                    else -> it.category == category
                }
            }
        }

    /** The servers offered for [language], null until the server answered. */
    fun serversOf(language: String): LanguageServerChoice? = choices[language]

    /** Shows [category], clearing the search. */
    fun select(category: SettingsCategory) {
        this.category = category
        query = ""
    }

    /**
     * Asks the server which servers it offers for each language, and which one is used. Asked again for the same
     * [connection] (a window shown again), it keeps what it has; a new connection (a restarted server) asks anew.
     * Never asked by the dialog of the agent window, which does not list the servers.
     */
    fun loadServers(connection: Long? = null) {
        if (SettingsCategory.LanguageServers in hidden) return
        val listedAlready = connection != null && connection == listedOn
        if (listedAlready && loaded) return
        scope.launch {
            try {
                for (language in SERVER_LANGUAGES) {
                    choices[language] = languageServers().choiceFor(language)
                }
                listedOn = connection
                serverError = null
            } catch (failure: IOException) {
                serverError = "Could not list the language servers: ${failure.message}"
            }
        }
    }

    /** Uses [candidate] for [language]: the running servers of the language switch to it. */
    fun useServer(language: String, candidate: String) {
        if (switchingTo != null) return
        switchingTo = candidate
        scope.launch {
            try {
                languageServers().use(language, candidate)
                choices[language]?.let { choices[language] = it.afterUse(candidate) }
                serverError = null
            } catch (failure: IOException) {
                serverError = "Could not change the language server: ${failure.message}"
            } finally {
                switchingTo = null
            }
        }
    }

    /**
     * Turns the servers of [language] off, or on again with the one chosen before (the recommended one when none was).
     * A server that cannot run here is never picked to turn a language on.
     */
    fun setServersEnabled(language: String, enabled: Boolean) {
        val choice = choices[language] ?: return
        val candidate = if (enabled) previousServerOf(choice) else LanguageServerChoice.DISABLED
        if (candidate == null) return
        useServer(language, candidate)
    }

    private fun previousServerOf(choice: LanguageServerChoice): String? {
        val chosen = choice.chosen
        if (chosen != null) return chosen
        val available = choice.candidates.firstOrNull { it.available }
        return available?.id
    }

    /** Stops what the dialog was waiting for. */
    fun close() = scope.cancel()
}

// The choice as it stands once [candidate] was used: off, or on with that server.
private fun LanguageServerChoice.afterUse(candidate: String): LanguageServerChoice =
    if (candidate == LanguageServerChoice.DISABLED) {
        copy(disabled = true)
    } else {
        copy(chosen = candidate, disabled = false)
    }
