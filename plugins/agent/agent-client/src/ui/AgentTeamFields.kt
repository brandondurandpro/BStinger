package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.AgentOptionValue
import org.example.kotlineditor.state.offeredRow
import org.example.kotlineditor.state.otherValues

private val FORM_MENU_MIN_WIDTH = 240.dp
private val CHIP_REMOVE_SIZE = 16.dp

/** The texts of the menu of a form field: its search field, its first row (`Default`, `None`) and its loading row. */
internal class FormMenuTexts(val filter: String, val firstRow: String, val loading: String)

/**
 * The values a form menu shows: those the agent [offered] (null while it is asked), then those the user [used]
 * elsewhere (in other teams) that the agent does not offer.
 */
internal class ChoiceSource(val offered: List<AgentOptionValue>?, val used: List<String>, val silent: Boolean = false)

// The frame of a form field, the same as IdeTextField: one click on it opens its menu.
@Composable
private fun FieldFrame(tag: String, modifier: Modifier, open: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = if (focused) IdeColors.Ring else IdeColors.InputBorder
    // Not `clickable`: it would merge the chips of the skills into the field, and hide their tags.
    val opens =
        Modifier
            .pointerInput(Unit) { detectTapGestures { open() } }
            .semantics {
                role = Role.Button
                onClick {
                    open()
                    true
                }
            }
    Row(
        modifier
            .focusRing(focused)
            .background(IdeColors.Input, IdeShapes.Medium)
            .border(1.dp, borderColor, IdeShapes.Medium)
            .heightIn(min = CONTROL_HEIGHT)
            .then(opens)
            .onKeyEvent { event -> opensWith(event, open) }
            .focusable(interactionSource = interaction)
            .testTag(tag)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

// Enter or Space on a focused field opens its menu.
private fun opensWith(event: KeyEvent, open: () -> Unit): Boolean {
    val pressed = event.type == KeyEventType.KeyDown
    val opening = event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar
    if (pressed && opening) open()
    return pressed && opening
}

/** A field that shows [display] and a chevron, and opens the menu [menu] draws under it; the menu is given its `close`. */
@Composable
internal fun SelectField(
    tag: String,
    modifier: Modifier,
    display: @Composable RowScope.() -> Unit,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val close = { open = false }
    Box(modifier) {
        FieldFrame(tag, Modifier.fillMaxWidth(), { open = true }) {
            display()
            IdeIcon(Lucide.ChevronDown, size = 14.dp)
        }
        val menuModifier = Modifier.widthIn(min = FORM_MENU_MIN_WIDTH, max = CHOICE_MENU_WIDTH).closesOnEscape(close)
        IdeDropdownMenu(open, close, menuModifier) { menu(close) }
    }
}

/** A [SelectField] of one value: [value] as is, or `Default` in a muted color when it is empty. */
@Composable
internal fun ValueSelectField(value: String, tag: String, modifier: Modifier, menu: @Composable (close: () -> Unit) -> Unit) {
    val display: @Composable RowScope.() -> Unit = {
        if (value.isBlank()) {
            Label("Default", Modifier.weight(1f), color = IdeColors.TextSecondary)
        } else {
            Label(value, Modifier.weight(1f))
        }
    }
    SelectField(tag, modifier, display, menu)
}

/** A [SelectField] of the [skills] chosen, as chips each with a button to remove it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkillsSelectField(
    skills: List<String>,
    tag: String,
    modifier: Modifier,
    remove: (String) -> Unit,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    val display: @Composable RowScope.() -> Unit = {
        if (skills.isEmpty()) {
            Label("No skill", Modifier.weight(1f), color = IdeColors.TextSecondary)
        } else {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (skill in skills) SkillChip(skill) { remove(skill) }
            }
        }
    }
    SelectField(tag, modifier, display, menu)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SkillChip(name: String, remove: () -> Unit) {
    Row(
        Modifier
            .background(IdeColors.Selection, IdeShapes.Small)
            .padding(start = 6.dp, end = 2.dp, top = 1.dp, bottom = 1.dp)
            .testTag(AgentTags.agentMemberSkill(name)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Label(name, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        TooltipArea(tooltip = { Tooltip("Remove skill") }) {
            Box(
                Modifier
                    .size(CHIP_REMOVE_SIZE)
                    .clickable(onClick = remove)
                    .testTag(AgentTags.agentMemberSkillRemove(name)),
                contentAlignment = Alignment.Center,
            ) {
                IdeIcon(Lucide.X, size = 10.dp)
            }
        }
    }
}

/**
 * The body of the menu of a form field, under its trigger [tag]: a search field, the first row ([FormMenuTexts.firstRow],
 * which [clear]s), the values the agent offers, those the user used and the agent does not offer, and a last row that
 * adds what was typed. [chosen] are the values stored now; [pick] receives the value to store (its display name).
 */
@Composable
internal fun FormChoiceMenu(
    tag: String,
    texts: FormMenuTexts,
    source: ChoiceSource,
    chosen: List<String>,
    loose: Boolean,
    pick: (String) -> Unit,
    clear: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    AgentFilterField(filter, "$tag.filter", texts.filter) { filter = it }
    val offered = source.offered
    val known = offered.orEmpty()
    val shownOffered = known.filter { it.matches(filter) }
    val wanted = filter.trim()
    val others = otherValues(known, chosen + source.used, loose)
    val shownOthers = others.filter { it.contains(wanted, ignoreCase = true) }
    val typedKnown = known.any { offeredRow(listOf(it), wanted, false) != null }
    val typedOther = others.any { it.equals(wanted, ignoreCase = true) }
    ChoiceScroller {
        ChoiceItem(texts.firstRow, null, chosen.isEmpty(), "$tag.default", clear)
        if (offered == null && !source.silent) MenuNote(texts.loading, "$tag.loading")
        if (offered == null && source.silent) MenuNote(SILENT, "$tag.unavailable")
        if (offered != null && offered.isEmpty()) MenuNote(UNAVAILABLE, "$tag.unavailable")
        for (value in shownOffered) {
            val isChosen = chosen.any { offeredRow(known, it, loose)?.value == value.value }
            ChoiceItem(value.name, value.description, isChosen, "$tag.choice.${value.name}") { pick(value.name) }
        }
        if (shownOthers.isNotEmpty()) {
            MenuSeparator()
            Label("Used in your teams", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = IdeColors.TextSecondary, fontSize = 11.sp)
            for (value in shownOthers) {
                val isChosen = chosen.any { it.equals(value, ignoreCase = true) }
                ChoiceItem(value, null, isChosen, "$tag.choice.$value") { pick(value) }
            }
        }
        val nothingShown = shownOffered.isEmpty() && shownOthers.isEmpty()
        if (wanted.isNotEmpty() && nothingShown) NoMatch(wanted)
        if (wanted.isNotEmpty() && !typedKnown && !typedOther) {
            IdeMenuItem(
                text = { Label("Use \"$wanted\"") },
                onClick = { pick(wanted) },
                modifier = Modifier.testTag("$tag.custom"),
                leadingIcon = { IdeIcon(Lucide.Plus, size = 14.dp) },
            )
        }
    }
}

private const val SILENT = "The agent does not answer: type a value."

private const val UNAVAILABLE = "The agent offers no list here: type a value."

@Composable
private fun MenuNote(text: String, tag: String) {
    Label(text, Modifier.padding(horizontal = 8.dp, vertical = 6.dp).testTag(tag), color = IdeColors.TextSecondary, fontSize = 12.sp)
}
