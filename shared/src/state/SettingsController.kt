package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.state.RemoteBundleSource
import org.example.kotlineditor.telemetry.Telemetry
import org.example.kotlineditor.ui.ThemeChoice

/**
 * The settings of the whole IDE, whatever the server shown: the editor font size, agent mode, the telemetry consent
 * and the Settings dialog. Those of a project (inlay hints, "Always Select Opened File") are the open project's, else
 * the saved ones, which the next project opened takes.
 */
@Stable
class SettingsController(
    private val preferences: PreferenceHub,
    private val scope: CoroutineScope,
    /** The project shown, if any. */
    private val openProject: () -> ProjectState?,
    /** The language servers of the server shown, for the Settings dialog. */
    private val languageServers: () -> LanguageServersState,
    /** Makes the telemetry the user agrees to, for an installation id; null measures nothing (previews, tests). */
    telemetryOf: ((instanceId: String) -> Telemetry)? = null,
) {
    /**
     * Feature flag of the agent (experimental, off by default): shows the Agent button of the toolbar, which opens
     * the agent window, and its shortcuts. Turning it off closes the agent window of the open project.
     */
    var agentModeEnabled by mutableStateOf(preferences[PreferenceKeys.AGENT_MODE]?.toBooleanStrictOrNull() ?: false)
        private set

    /** Whether the IDE opens in agent mode rather than in the editors: the last project is then reopened at launch. */
    var agentModeByDefault by mutableStateOf(savedAgentModeByDefault())

    /** Whether a project opens in agent mode: agent mode is on and is the user's default. */
    val startsInAgentMode: Boolean get() = agentModeEnabled && agentModeByDefault

    /** Command line of the agent of agent mode (any ACP agent); blank for the agent of the registry. */
    var agentCommand by mutableStateOf(preferences[PreferenceKeys.AGENT_COMMAND].orEmpty())

    /** Id of the agent of the ACP registry for agent mode, blank for [DEFAULT_AGENT]; a command line wins over it. */
    var agentRegistry by mutableStateOf(preferences[PreferenceKeys.AGENT_REGISTRY].orEmpty())

    /** Where the server installed on a remote host comes from: a local bundle, or the GitHub release. */
    var remoteBundleSource by mutableStateOf(
        RemoteBundleSource.fromSaved(preferences[PreferenceKeys.REMOTE_BUNDLE_SOURCE]),
    )

    /** Whether the user shares anonymous performance measures: asked on the welcome screen, changed in the menu. */
    val telemetry = TelemetryConsent(preferences.known, telemetryOf)

    /** Editor font size in points, shared by every editor (Alt+Shift+. / Alt+Shift+, / Ctrl+wheel). */
    var editorFontSize by mutableFloatStateOf(savedFontSize())
        private set

    /** The theme of the IDE: dark, light, or the operating system's. */
    var theme by mutableStateOf(ThemeChoice.fromSaved(preferences[PreferenceKeys.THEME]))

    /** The dialogs of the settings, one per window: the IDE window's, and the agent window's, opened apart. */
    val dialogs = SettingsDialogs(languageServers, scope)

    /** How the inlay hints show: those of the open project, else the saved ones. */
    var inlayHintsMode: InlayHintsMode
        get() = openProject()?.inlayHintsMode ?: InlayHintsMode.fromSaved(preferences[PreferenceKeys.INLAY_HINTS])
        set(value) {
            val project = openProject()
            if (project != null) {
                project.inlayHintsMode = value
            } else {
                preferences.put(PreferenceKeys.INLAY_HINTS, value.name)
            }
        }

    /** "Always Select Opened File": the open project's, else the saved one (as [inlayHintsMode]). */
    var autoSelectOpenedFile: Boolean
        get() = openProject()?.autoSelectOpenedFile ?: autoSelectOf(preferences.all)
        set(value) {
            val project = openProject()
            if (project != null) {
                project.autoSelectOpenedFile = value
            } else {
                preferences.put(PreferenceKeys.AUTO_SELECT_OPENED_FILE, value.toString())
            }
        }

    /** The agent of agent mode, as set: its command line, else the agent of the registry. */
    fun agentCommandLine(): AgentCommand = agentCommandOf(agentCommand, agentRegistry)

    fun zoomEditor(delta: Float) {
        editorFontSize = (editorFontSize + delta).coerceIn(MIN_EDITOR_FONT_SIZE, MAX_EDITOR_FONT_SIZE)
    }

    fun resetEditorZoom() {
        editorFontSize = DEFAULT_EDITOR_FONT_SIZE
    }

    fun enableAgentMode(enabled: Boolean) {
        agentModeEnabled = enabled
        if (!enabled) openProject()?.agentMode = false
    }

    /** The preferences these settings keep. */
    fun toPreferences(): Map<String, String> {
        val fontSize = PreferenceKeys.EDITOR_FONT_SIZE to editorFontSize.toString()
        val agentMode = PreferenceKeys.AGENT_MODE to agentModeEnabled.toString()
        val byDefault = PreferenceKeys.AGENT_MODE_BY_DEFAULT to agentModeByDefault.toString()
        val agentCommandLine = PreferenceKeys.AGENT_COMMAND to agentCommand
        val agentOfRegistry = PreferenceKeys.AGENT_REGISTRY to agentRegistry
        val themeName = PreferenceKeys.THEME to theme.name
        val bundleSource = PreferenceKeys.REMOTE_BUNDLE_SOURCE to remoteBundleSource.name
        val values = mapOf(fontSize, themeName, agentMode, byDefault, agentCommandLine, agentOfRegistry, bundleSource)
        return values + telemetry.toPreferences()
    }

    private fun savedAgentModeByDefault(): Boolean =
        preferences[PreferenceKeys.AGENT_MODE_BY_DEFAULT]?.toBooleanStrictOrNull() ?: false

    private fun savedFontSize(): Float =
        preferences[PreferenceKeys.EDITOR_FONT_SIZE]
            ?.toFloatOrNull()
            ?.takeIf { it.isFinite() }
            ?.coerceIn(MIN_EDITOR_FONT_SIZE, MAX_EDITOR_FONT_SIZE)
            ?: DEFAULT_EDITOR_FONT_SIZE

    companion object {
        const val MIN_EDITOR_FONT_SIZE = 8f
        const val MAX_EDITOR_FONT_SIZE = 40f
    }
}

/**
 * The Settings dialogs, one per window: the IDE window's ([ide]), and the agent window's ([agent]), which are the
 * two products' own. Both read and change the same settings; each window shows and closes its own.
 */
@Stable
class SettingsDialogs(
    /** The language servers of the server shown, for the dialog of the servers. */
    private val languageServers: () -> LanguageServersState,
    private val scope: CoroutineScope,
) {
    /** The dialog of the IDE window, null while it is closed. */
    var ide: SettingsState? by mutableStateOf(null)
        private set

    /** The dialog of the agent window, null while it is closed. */
    var agent: SettingsState? by mutableStateOf(null)
        private set

    /** Opens the dialog of the IDE window on [category], or on its first one. */
    fun openIde(category: SettingsCategory = SettingsCategory.Editor) {
        ide = opened(ide, category, emptySet())
    }

    /** Opens the dialog of the agent window, its own, on [category]: it does not list the IDE's categories. */
    fun openAgent(category: SettingsCategory = SettingsCategory.Editor) {
        agent = opened(agent, category, IDE_ONLY_CATEGORIES)
    }

    fun closeIde() {
        ide?.close()
        ide = null
    }

    fun closeAgent() {
        agent?.close()
        agent = null
    }

    // A dialog opened on [category] without the [hidden] categories, the one shown before closed first (it watches
    // the servers).
    private fun opened(
        current: SettingsState?,
        category: SettingsCategory,
        hidden: Set<SettingsCategory>,
    ): SettingsState {
        current?.close()
        return SettingsState(languageServers, scope, hidden).apply {
            select(category)
            loadServers()
        }
    }
}
