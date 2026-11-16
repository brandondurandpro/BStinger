package org.example.kotlineditor.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.plugin.ClientContributions
import org.example.kotlineditor.plugin.ClientPlugin
import org.example.kotlineditor.plugin.StatusIndicatorContribution
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.EDITOR_SERVICE
import org.example.kotlineditor.state.PROJECT_STATE_SERVICE
import org.example.kotlineditor.state.ProjectHost

/**
 * The built-in indicators of the status bar about the document of the active tab: where the caret is, the line
 * endings, the encoding and the language. Hidden while agent mode shows the conversations.
 */
class StatusIndicatorsClientPlugin : ClientPlugin {
    override fun register(contributions: ClientContributions) {
        contributions.statusIndicator(caretIndicator())
        contributions.statusIndicator(separatorIndicator())
        contributions.statusIndicator(encodingIndicator())
        contributions.statusIndicator(languageIndicator())
    }
}

// The active document, while the IDE shows the editors; null in agent mode, or with no tab open.
private fun ProjectHost.activeDocument(): Document? {
    if (service(PROJECT_STATE_SERVICE).agentMode) return null
    return service(EDITOR_SERVICE).activeTab?.document
}

private fun caretIndicator() =
    StatusIndicatorContribution("Caret", "Cursor position") { project ->
        project.activeDocument()?.let { document ->
            val caret = document.caret
            // Cheap summary: never materialize a potentially huge selection just to count it.
            val range = document.selectionRange()
            val selection =
                when {
                    range == null -> ""
                    range.first.line == range.second.line -> " (${range.second.column - range.first.column} chars)"
                    else -> " (${range.second.line - range.first.line + 1} lines)"
                }
            Label(
                "${caret.line + 1}:${caret.column + 1}$selection",
                Modifier.testTag(AppTags.STATUS_CARET),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
    }

private fun separatorIndicator() =
    StatusIndicatorContribution("LineSeparator", "Line endings") { project ->
        project.activeDocument()?.let { document ->
            val separator = if (document.lineSeparator == "\r\n") "CRLF" else "LF"
            Label(
                separator,
                Modifier.testTag(AppTags.STATUS_LINE_SEPARATOR),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
    }

private fun encodingIndicator() =
    StatusIndicatorContribution("Encoding", "Encoding") { project ->
        project.activeDocument()?.let {
            Label(
                "UTF-8",
                Modifier.testTag(AppTags.STATUS_ENCODING),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
    }

private fun languageIndicator() =
    StatusIndicatorContribution("Language", "Language") { project ->
        project.activeDocument()?.let { document ->
            Label(
                document.syntax?.spec?.name ?: document.language.name,
                Modifier.testTag(AppTags.STATUS_LANGUAGE),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
