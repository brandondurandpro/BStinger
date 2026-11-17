package org.example.kotlineditor.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// What the skill lists share: the owners of the skills (a repository, the project, the IDE) told apart by a colour and a
// header, which folds the skills of its owner away.

// The width of the colour stripe at the start of an owner's header and of its skills.
private val STRIPE_WIDTH = 3.dp

/** The colour of the owner at [index] in the list; the same owner is always the same colour, six are told apart. */
@Composable
internal fun ownerColor(index: Int): Color {
    val colors = listOf(IdeColors.Accent, IdeColors.GitAdded, IdeColors.GitModified, IdeColors.GitAhead, IdeColors.GitUntracked, IdeColors.GitDeleted)
    return colors[index.mod(colors.size)]
}

/**
 * The header of an owner: its [color], a chevron, its [title] (tagged [titleTag]) and a click anywhere on it, tagged
 * [toggleTag], that folds its skills away or shows them ([folded]).
 */
@Composable
internal fun OwnerHeader(title: String, color: Color, folded: Boolean, titleTag: String, toggleTag: String, onToggle: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp).background(color.copy(alpha = HEADER_TINT), IdeShapes.Medium)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(STRIPE_WIDTH).height(HEADER_HEIGHT).background(color, IdeShapes.Medium))
            Label(if (folded) "▸" else "▾", Modifier.padding(start = 8.dp), color = color, fontSize = 12.sp)
            Label(title, Modifier.padding(start = 6.dp).testTag(titleTag), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        // Over the whole header, beside its texts: a clickable row would merge them into one node.
        Box(Modifier.matchParentSize().clickable(onClick = onToggle).testTag(toggleTag))
    }
}

/** A thin stripe of [color] at the start of a skill of an owner, which ties it to its header. */
internal fun Modifier.ownerStripe(color: Color): Modifier =
    drawBehind { drawRect(color.copy(alpha = STRIPE_ALPHA), size = Size(STRIPE_WIDTH.toPx() / 2, size.height)) }

/**
 * The scrollbar of [state], as high as the room it is given. A menu measures its content by intrinsic sizes, and a bar
 * that fills the height has none to give: this one says it needs no room, and fills what it is given when it is placed.
 */
@Composable
internal fun MenuScrollbar(state: ScrollState, tag: String, modifier: Modifier = Modifier) {
    Layout({ VerticalScrollbar(rememberScrollbarAdapter(state)) }, modifier.testTag(tag), NoIntrinsicSize)
}

private object NoIntrinsicSize : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        val bar = measurables.firstOrNull()?.measure(constraints.copy(minHeight = height, maxHeight = height))
        return layout(bar?.width ?: 0, height) { bar?.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = 0

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = 0

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = 0

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = 0
}

private const val HEADER_TINT = 0.16f
private const val STRIPE_ALPHA = 0.6f
private val HEADER_HEIGHT = 24.dp
