package org.example.kotlineditor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Padding inside a menu, around its items (shadcn/ui's `p-1`).
private val MENU_PADDING = 4.dp

// Height of a menu item of one line (shadcn/ui's `py-1.5` around a 13 sp line).
private val ITEM_HEIGHT = 30.dp

private const val DISABLED_ALPHA = 0.6f

/** shadcn/ui's dropdown menu: a bordered popover with a soft shadow and inset, rounded items. */
@Composable
fun IdeDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.padding(horizontal = MENU_PADDING),
        offset = offset,
        shape = IdeShapes.Large,
        containerColor = IdeColors.Popup,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, IdeColors.Border),
        content = content,
    )
}

/**
 * An item of [IdeDropdownMenu] (shadcn/ui's `DropdownMenuItem`): a compact row, rounded, highlighted under the
 * pointer; the text and icons take the content color, muted when it is disabled.
 */
@Composable
fun IdeMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = if (hovered && enabled) IdeColors.Selection else Color.Transparent
    val content = if (enabled) IdeColors.Text else IdeColors.TextSecondary.copy(alpha = DISABLED_ALPHA)
    val style = TextStyle(fontFamily = IdeFonts.ui, fontSize = 13.sp, color = content)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = ITEM_HEIGHT)
            .clip(IdeShapes.Small)
            .background(background)
            .hoverable(interaction, enabled)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides content, LocalTextStyle provides style) {
            leadingIcon?.invoke()
            Box(Modifier.weight(1f)) { text() }
            trailingIcon?.invoke()
        }
    }
}
