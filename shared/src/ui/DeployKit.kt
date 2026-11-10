package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Building blocks of the deployment screens, in the spirit of shadcn/ui: quiet outlines, one strong button, muted
// labels, rounded tables. Made of the theme's colors, so they follow it.

private val CONTROL_SHAPE = RoundedCornerShape(6.dp)
private val TABLE_SHAPE = RoundedCornerShape(8.dp)
private val TABLE_HEADER_SHAPE = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
private val CONTROL_HEIGHT = 30.dp
private const val TINT_ALPHA = 0.16f
private const val MUTED_ALPHA = 0.5f
private const val TOOLTIP_LINES = 30

/** How strongly a [DeployButton] stands out. */
enum class DeployDeployButtonVariant {
    /** The one action of the screen: filled with the text color. */
    Primary,

    /** A secondary action: an outline. */
    Outline,

    /** A minor action: no frame until hovered. */
    Ghost,
}

/** A button of the deployment screens. */
@Composable
internal fun DeployButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: DeployDeployButtonVariant = DeployDeployButtonVariant.Outline,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val primary = variant == DeployDeployButtonVariant.Primary
    val background =
        when {
            primary && hovered && enabled -> IdeColors.Text.copy(alpha = 0.9f)
            primary -> IdeColors.Text
            hovered && enabled -> IdeColors.Hover
            else -> Color.Transparent
        }
    val outline =
        if (variant == DeployDeployButtonVariant.Outline) {
            Modifier.border(
                1.dp,
                IdeColors.Separator,
                CONTROL_SHAPE,
            )
        } else {
            Modifier
        }
    val content =
        when {
            !enabled -> IdeColors.TextSecondary
            primary -> IdeColors.Panel
            else -> IdeColors.Text
        }
    Box(
        modifier
            .background(background, CONTROL_SHAPE)
            .then(outline)
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .heightIn(min = CONTROL_HEIGHT)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, color = content, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** A pill with a dot, for a state: the trigger of a deployment, where it goes. */
@Composable
internal fun DotBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            // Its text is the badge's, for whoever reads the screen.
            .semantics(mergeDescendants = true) {}
            .background(color.copy(alpha = TINT_ALPHA), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Label(text, color = color, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

/** One choice of a [Segmented] control: its text, its tag, and whether it is the chosen one. */
internal class Segment(val text: String, val tag: String, val selected: Boolean, val onClick: () -> Unit)

/** Choices side by side in a sunken frame, the chosen one raised: Local or SSH, Manual or Automatic. */
@Composable
internal fun Segmented(segments: List<Segment>, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(IdeColors.Input, CONTROL_SHAPE)
            .border(1.dp, IdeColors.Separator, CONTROL_SHAPE)
            .padding(2.dp),
    ) {
        for (segment in segments) {
            val background = if (segment.selected) IdeColors.Hover else Color.Transparent
            val color = if (segment.selected) IdeColors.Text else IdeColors.TextSecondary
            Box(
                Modifier
                    .background(background, RoundedCornerShape(4.dp))
                    .clickable(onClick = segment.onClick)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag(segment.tag),
                contentAlignment = Alignment.Center,
            ) {
                Label(segment.text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** A field's name, and under it what it is for, in a muted tone. */
@Composable
internal fun FieldHeading(title: String, description: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.padding(top = 14.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            trailing()
        }
        if (description != null) {
            Label(description, color = IdeColors.TextSecondary, fontSize = 12.sp, maxLines = 2)
        }
    }
}

/** A rounded, outlined table: the [header] row on a muted ground, then the rows. */
@Composable
internal fun TableFrame(
    modifier: Modifier = Modifier,
    header: @Composable RowScope.() -> Unit,
    rows: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().border(1.dp, IdeColors.Separator, TABLE_SHAPE)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(IdeColors.Hover.copy(alpha = MUTED_ALPHA), TABLE_HEADER_SHAPE)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            header()
        }
        rows()
    }
}

/** The name of a column of a [TableFrame]. */
@Composable
internal fun ColumnTitle(text: String, modifier: Modifier = Modifier) {
    Label(text, modifier, color = IdeColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

/** The line between two rows of a table. */
@Composable
internal fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
}

/** [content], with [text] (several lines allowed) shown in a small popup while the pointer is over it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HoverHint(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Box(
                Modifier
                    .background(IdeColors.Popup, CONTROL_SHAPE)
                    .border(1.dp, IdeColors.Separator, CONTROL_SHAPE)
                    .padding(8.dp),
            ) {
                Label(text, fontSize = 12.sp, maxLines = TOOLTIP_LINES)
            }
        },
        modifier = modifier,
    ) {
        content()
    }
}

/** A path or a name of a file, in the code font, on one line. */
@Composable
internal fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color = IdeColors.Text) {
    BasicText(
        text,
        modifier,
        style = LocalCodeStyle.current.copy(color = color, fontSize = 12.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
