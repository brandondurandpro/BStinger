package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// shadcn/ui's display components: islands, segmented control, section label, card, status dot and progress bar.

/** Space between two islands, which is also the handle that resizes them. */
val ISLAND_GAP = 6.dp

/**
 * An island (IntelliJ's Islands theme): a rounded card with a hairline border, floating on [IdeColors.Frame]; what
 * it holds is clipped to its corners.
 */
fun Modifier.island(background: Color = IdeColors.Panel): Modifier =
    clip(IdeShapes.Island)
        .background(background)
        .border(1.dp, IdeColors.Border, IdeShapes.Island)

/**
 * shadcn/ui's tabs list used as a segmented control: a muted track holding the [options], the [selected] one raised
 * on the background color. [tagOf] tags each option, which is marked selected for the tests and screen readers.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    labelOf: (T) -> String = { it.toString() },
    tagOf: (T) -> String? = { null },
) {
    Row(
        modifier.background(IdeColors.Selection.copy(alpha = TRACK_ALPHA), IdeShapes.Large).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (option in options) SegmentedOption(labelOf(option), option == selected, tagOf(option)) { onSelect(option) }
    }
}

private const val TRACK_ALPHA = 0.7f

@Composable
private fun SegmentedOption(label: String, chosen: Boolean, tag: String?, onClick: () -> Unit) {
    val raised =
        if (chosen) {
            Modifier.background(IdeColors.Editor, IdeShapes.Medium).border(1.dp, IdeColors.Border, IdeShapes.Medium)
        } else {
            Modifier
        }
    val tagged = if (tag != null) Modifier.testTag(tag) else Modifier
    Label(
        label,
        Modifier
            .then(raised)
            .clip(IdeShapes.Medium)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { selected = chosen }
            .then(tagged)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = if (chosen) IdeColors.Text else IdeColors.TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** Small uppercase heading of a group of rows or cards (`ACTIVE SERVERS`). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Label(
        text.uppercase(),
        modifier,
        color = IdeColors.TextSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
    )
}

/** shadcn/ui's card: a bordered, rounded block on the background color. */
fun Modifier.card(): Modifier =
    clip(IdeShapes.Large)
        .background(IdeColors.Editor)
        .border(1.dp, IdeColors.Border, IdeShapes.Large)

/** The colored dot of a status, such as green while a server runs. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).background(color, IdeShapes.Full))
}

/** shadcn/ui's progress bar: the used [fraction] in the text color, on a muted track. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val used = fraction.coerceIn(0f, 1f)
    Box(modifier.height(4.dp).background(IdeColors.Selection, IdeShapes.Full)) {
        if (used > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(used).background(IdeColors.Text, IdeShapes.Full))
    }
}
