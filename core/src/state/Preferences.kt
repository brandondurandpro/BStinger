package org.example.kotlineditor.state

/** Where the user's IDE preferences live: flat string keys and values, so any backend can hold them. */
interface PreferenceStore {
    /** The saved preferences; empty when there are none yet or when they cannot be read. */
    fun load(): Map<String, String>

    /** Replaces the saved preferences by [values]. */
    fun save(values: Map<String, String>)
}

/** Preferences kept in memory only: the default for tests and previews. */
class InMemoryPreferenceStore(initial: Map<String, String> = emptyMap()) : PreferenceStore {
    @Volatile
    private var values = initial

    override fun load(): Map<String, String> = values

    override fun save(values: Map<String, String>) {
        this.values = values.toMap()
    }
}

/** Keys of the persisted preferences. The tool window layout adds its own keys, prefixed by [LAYOUT_PREFIX]. */
object PreferenceKeys {
    const val EDITOR_FONT_SIZE = "editor.fontSize"
    const val AUTO_SELECT_OPENED_FILE = "project.autoSelectOpenedFile"
    const val AGENT_MODE = "experimental.agentMode"
    const val AGENT_MODE_BY_DEFAULT = "experimental.agentModeByDefault"

    /** Width of the conversation list of agent mode, in dp. */
    const val AGENT_CONVERSATIONS_WIDTH = "agent.conversationsWidth"

    /** The agents of the registry the user chose, one per line: the history lists the sessions of each. */
    const val AGENT_USED = "agent.used"

    /** The agent of the registry last chosen in a conversation; blank for the agent of the settings. */
    const val AGENT_LAST_CHOSEN = "agent.lastChosen"

    /** The prompt templates of the user: one per line, `name`, a tab, the text with `\\`, newlines and tabs escaped. */
    const val AGENT_TEMPLATES = "agent.templates"

    /** Per project (the key ends with its path): its prompt templates, in the format of [AGENT_TEMPLATES]. */
    const val AGENT_TEMPLATES_PREFIX = "agent.templates."

    /** Per project (the key ends with its path): the sessions open in tabs of agent mode, one per line, in order. */
    const val AGENT_OPEN_PREFIX = "agent.open."

    /** Per project: the session of the tab that was active. */
    const val AGENT_ACTIVE_PREFIX = "agent.active."

    /** Per project: the titles the user gave to conversations, one `session<TAB>title` per line. */
    const val AGENT_TITLES_PREFIX = "agent.titles."

    /** Per project: the sessions of the conversations the user pinned to the top of the list, one per line. */
    const val AGENT_PINNED_PREFIX = "agent.pinned."

    /** Per project: the sessions of the conversations the user archived (hidden from the list), one per line. */
    const val AGENT_ARCHIVED_PREFIX = "agent.archived."

    const val AGENT_COMMAND = "agent.command"
    const val AGENT_REGISTRY = "agent.registry"
    const val INLAY_HINTS = "editor.inlayHints"
    const val DIFF_SIDE_BY_SIDE = "editor.diffSideBySide"
    const val LAYOUT_PREFIX = "layout."

    /** Branches pinned in the toolbar, per project: the key ends with the project's path (`GitState`). */
    const val PINNED_BRANCHES_PREFIX = "git.pinnedBranches."

    /** Whether the user agreed to share anonymous performance measures; absent until asked, which means no. */
    const val TELEMETRY_ENABLED = "telemetry.enabled"

    /** The random id of this installation in the measures, made when the user agreed. */
    const val TELEMETRY_INSTANCE = "telemetry.instanceId"

    /** Last directory the Open Project dialog browsed, as the absolute path chosen. */
    const val PICKER_DIRECTORY = "picker.directory"
}

/** Font size of the editors, in points, until the user zooms (Ctrl+wheel, Ctrl+=). */
const val DEFAULT_EDITOR_FONT_SIZE = 13f
