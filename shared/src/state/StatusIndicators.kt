package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** An indicator of the status bar the user can keep shown or hide. What is wrong (errors, the link) is always shown. */
enum class StatusIndicator(
    /** The name in the menu. */
    val label: String,
) {
    LanguageServers("Language servers"),
    Memory("Memory"),
    Cpu("CPU"),
    Caret("Cursor position"),
    LineSeparator("Line endings"),
    Encoding("Encoding"),
    Language("Language"),
}

/**
 * Which indicators of the status bar are shown. One choice for the IDE, whatever the project and the worktree shown:
 * it is saved. All of them are shown until the user hides one, so an indicator added later appears by itself.
 */
@Stable
class StatusIndicators(preferences: Map<String, String> = emptyMap()) {
    private var hidden: Set<StatusIndicator> by mutableStateOf(parse(preferences[PreferenceKeys.STATUS_HIDDEN]))

    /** Whether [indicator] is shown. */
    fun isShown(indicator: StatusIndicator): Boolean = indicator !in hidden

    /** Shows or hides [indicator]. */
    fun setShown(indicator: StatusIndicator, shown: Boolean) {
        hidden = if (shown) hidden - indicator else hidden + indicator
    }

    /** The preference this choice keeps: the hidden ones, by name, in the order of the menu. */
    fun toPreferences(): Map<String, String> {
        val names = StatusIndicator.entries.filter { it in hidden }.joinToString(",") { it.name }
        return mapOf(PreferenceKeys.STATUS_HIDDEN to names)
    }

    private companion object {
        // A name this version does not know (a preference saved by another one) is ignored.
        fun parse(value: String?): Set<StatusIndicator> {
            val names = value.orEmpty().split(',').map { it.trim() }
            return StatusIndicator.entries.filter { it.name in names }.toSet()
        }
    }
}
