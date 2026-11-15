package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import org.example.kotlineditor.ui.AgentTags

/** Opens the Team window of the conversation list, then the editor of a new team (its manager open). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.openNewTeam() {
    awaitTag(AgentTags.AGENT_TEAMS, timeout = AGENT_TIMEOUT).performClick()
    awaitTag(AgentTags.AGENT_TEAM_NEW).performClick()
    awaitTag(AgentTags.AGENT_TEAM_NAME)
}

/** Opens the menu of the select field [trigger] (model, effort or skills of the open member). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.openSelect(trigger: String) {
    awaitTag(trigger).performClick()
    awaitTag("$trigger.filter")
}

/** Chooses the row [name] of the menu of [trigger] (the menu closes for a model or an effort). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.chooseInSelect(trigger: String, name: String) {
    openSelect(trigger)
    awaitTag("$trigger.choice.$name", timeout = AGENT_TIMEOUT).performScrollTo().performClick()
}

/** Types [text] in the filter of the open menu of [trigger] and clicks its `Use "<text>"` row. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.useTyped(trigger: String, text: String) {
    awaitTag("$trigger.filter").performTextReplacement(text)
    awaitTag("$trigger.custom").performScrollTo().performClick()
}

/** Closes the open menu of [trigger] with Escape. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.escapeSelect(trigger: String) {
    val filter = awaitTag("$trigger.filter")
    filter.performClick()
    filter.performKeyInput { pressKey(Key.Escape) }
    awaitNoTag("$trigger.filter")
}

/** The text a select field shows. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.shownSelect(trigger: String): String = textOf(trigger)

/** A click on a button that asks "Sure?", then the click that confirms. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.clickTwice(tag: String) {
    awaitTag(tag).performClick()
    awaitTag(tag).performClick()
}
