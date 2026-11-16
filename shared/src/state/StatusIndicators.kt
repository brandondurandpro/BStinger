package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which indicators of the status bar are shown, by the key of their contribution. One choice for the IDE, whatever
 * the project and the worktree shown: it is saved. All of them are shown until the user hides one, so an indicator
 * added later appears by itself.
 */
@Stable
class StatusIndicators(preferences: Map<String, String> = emptyMap()) {
    private var hidden: Set<String> by mutableStateOf(parse(preferences[PreferenceKeys.STATUS_HIDDEN]))

    /** Whether the indicator contributed under [key] is shown. */
    fun isShown(key: String): Boolean = key !in hidden

    /** Shows or hides the indicator contributed under [key]. */
    fun setShown(key: String, shown: Boolean) {
        hidden = if (shown) hidden - key else hidden + key
    }

    /** The preference this choice keeps: the hidden ones, by key, alphabetical so it is stable. */
    fun toPreferences(): Map<String, String> = mapOf(PreferenceKeys.STATUS_HIDDEN to hidden.sorted().joinToString(","))

    private companion object {
        fun parse(value: String?): Set<String> =
            value
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
    }
}
