package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext

/**
 * The preferences of the IDE: those last saved, and the store they are written to. Each part of the IDE gives what it
 * keeps (see [AppState]); they are written together, each time one of them changes.
 */
@Stable
class PreferenceHub(private val store: PreferenceStore) {
    /** The preferences last known: read at start, then each time they are saved. */
    var known: Map<String, String> by mutableStateOf(store.load())
        private set

    // Kept without being saved at once (the directory the Open Project dialog browses): written with the next save.
    private var quiet: Map<String, String> = emptyMap()

    /** Everything known, including what waits for the next save: what a new project starts from. */
    val all: Map<String, String> get() = known + quiet

    operator fun get(key: String): String? = quiet[key] ?: known[key]

    /** Sets [key], which is saved right away. */
    fun put(key: String, value: String) {
        known = known + (key to value)
    }

    /** Sets [key] without saving it now: it is written with the next save. */
    fun putQuietly(key: String, value: String) {
        quiet = quiet + (key to value)
    }

    /** Writes the preferences, off the UI thread, each time [current] or what is known changes. */
    suspend fun saveOnChange(current: () -> Map<String, String>) {
        snapshotFlow { known + quiet + current() }.drop(1).collect { preferences ->
            known = preferences
            withContext(Dispatchers.IO) { runCatching { store.save(preferences) } }
        }
    }

    /** Writes the preferences now (when the IDE exits). */
    fun saveNow(current: () -> Map<String, String>) {
        runCatching { store.save(known + quiet + current()) }
    }
}
