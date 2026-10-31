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
    const val EDITOR_RENDERER = "editor.renderer"
    const val AUTO_SELECT_OPENED_FILE = "project.autoSelectOpenedFile"
    const val LAYOUT_PREFIX = "layout."
}
