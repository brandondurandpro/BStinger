package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How the diff viewer lays out its two versions: side by side (two panes) or unified. One for the IDE, whatever the
 * project and the worktree shown: the choice is saved and every diff opens the way it was last left.
 */
@Stable
class DiffLayout(preferences: Map<String, String> = emptyMap()) {
    var sideBySide by mutableStateOf(preferences[PreferenceKeys.DIFF_SIDE_BY_SIDE]?.toBooleanStrictOrNull() ?: false)

    /** The preference this layout keeps. */
    fun toPreferences(): Map<String, String> = mapOf(PreferenceKeys.DIFF_SIDE_BY_SIDE to sideBySide.toString())
}
