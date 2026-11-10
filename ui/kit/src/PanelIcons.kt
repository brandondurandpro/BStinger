package org.example.kotlineditor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// Icons of the buttons in the panels' headers.

/** A plus, for the buttons that add something (a terminal tab). */
@Composable
fun PlusIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    IdeIcon(Lucide.Plus, modifier, color)
}

/** A bar, for the buttons that minimize (hide) a panel. */
@Composable
fun MinimizeIcon(modifier: Modifier = Modifier, color: Color = IdeColors.TextSecondary) {
    IdeIcon(Lucide.Minus, modifier, color)
}
