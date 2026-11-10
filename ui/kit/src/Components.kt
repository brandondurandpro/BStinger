package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val DISABLED_ALPHA = 0.5f

private const val RING_ALPHA = 0.5f

private const val PRIMARY_HOVER_ALPHA = 0.9f

// Width of shadcn/ui's focus ring (`ring-[3px]`), drawn outside the control.
private val RING_WIDTH = 3.dp

// Radius of [IdeShapes.Medium], which the focus ring follows.
private val CONTROL_RADIUS = 6.dp

/** Height of buttons and inputs (shadcn/ui's `h-8`, its small size, which suits a dense IDE). */
val CONTROL_HEIGHT = 32.dp

/**
 * shadcn/ui's focus ring: a soft halo of [IdeColors.Ring] around a focused control, drawn outside its bounds so that
 * it never moves the layout.
 */
fun Modifier.focusRing(focused: Boolean, radius: Dp = CONTROL_RADIUS): Modifier =
    if (!focused) {
        this
    } else {
        drawBehind {
            val ring = RING_WIDTH.toPx()
            val half = ring / 2
            drawRoundRect(
                color = IdeColors.Ring.copy(alpha = RING_ALPHA),
                topLeft = Offset(-half, -half),
                size = Size(size.width + ring, size.height + ring),
                cornerRadius = CornerRadius(radius.toPx() + half),
                style = Stroke(ring),
            )
        }
    }

/**
 * A floating surface (shadcn/ui's popover, card and dialog): its background, a hairline border and a soft shadow
 * when [elevation] is above zero.
 */
fun Modifier.surface(
    shape: Shape = IdeShapes.Large,
    background: Color = IdeColors.Popup,
    border: Color = IdeColors.Border,
    elevation: Dp = 0.dp,
): Modifier {
    val shadowed =
        if (elevation > 0.dp) {
            shadow(elevation, shape, clip = false, ambientColor = IdeColors.Shadow, spotColor = IdeColors.Shadow)
        } else {
            this
        }
    return shadowed.background(background, shape).border(1.dp, border, shape)
}

/** Single-line text field styled like shadcn/ui's input. */
@Composable
fun IdeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onSubmit: () -> Unit = {},
    monospace: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val textStyle =
        if (monospace) {
            LocalCodeStyle.current.copy(color = IdeColors.Text)
        } else {
            TextStyle(color = IdeColors.Text, fontSize = 13.sp, fontFamily = IdeFonts.ui)
        }
    val borderColor = if (focused) IdeColors.Ring else IdeColors.InputBorder
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        cursorBrush = SolidColor(IdeColors.Caret),
        textStyle = textStyle,
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        decorationBox = { inner ->
            Row(
                Modifier
                    .focusRing(focused)
                    .background(IdeColors.Input, IdeShapes.Medium)
                    .border(1.dp, borderColor, IdeShapes.Medium)
                    .heightIn(min = CONTROL_HEIGHT)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                leading?.invoke()
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Label(placeholder, color = IdeColors.TextSecondary)
                    inner()
                }
                trailing?.invoke(this)
            }
        },
    )
}

/** The variants of shadcn/ui's button. */
enum class ButtonVariant {
    /** Filled with the primary color: the action a dialog is for. */
    Default,

    /** Filled with a muted color: a secondary action that still stands out. */
    Secondary,

    /** Bordered and transparent: the ordinary button. */
    Outline,

    /** Only a hover background: toolbar and inline actions. */
    Ghost,

    /** Filled red: what deletes or throws work away. */
    Destructive,
}

/** Colors of a button: its fill at rest and under the pointer, its text, and its border if it has one. */
private class ButtonColors(val rest: Color, val hovered: Color, val content: Color, val border: Color? = null)

private fun buttonColors(variant: ButtonVariant): ButtonColors =
    when (variant) {
        ButtonVariant.Default -> {
            ButtonColors(IdeColors.Accent, IdeColors.Accent.copy(alpha = PRIMARY_HOVER_ALPHA), IdeColors.OnAccent)
        }

        ButtonVariant.Secondary -> {
            ButtonColors(IdeColors.Selection, IdeColors.Hover, IdeColors.Text)
        }

        ButtonVariant.Outline -> {
            ButtonColors(IdeColors.Input, IdeColors.Hover, IdeColors.Text, IdeColors.InputBorder)
        }

        ButtonVariant.Ghost -> {
            ButtonColors(Color.Transparent, IdeColors.Hover, IdeColors.Text)
        }

        ButtonVariant.Destructive -> {
            ButtonColors(IdeColors.Error, IdeColors.Error.copy(alpha = PRIMARY_HOVER_ALPHA), Color.White)
        }
    }

/**
 * shadcn/ui's button: [primary] is its default (filled) variant, the others are outlined unless [variant] says
 * otherwise.
 */
@Composable
fun IdeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    variant: ButtonVariant = if (primary) ButtonVariant.Default else ButtonVariant.Outline,
    /** A Lucide icon before the text, like shadcn/ui's buttons with an icon. */
    icon: LucideIcon? = null,
    /** shadcn/ui's `size="sm"`: shorter, for the actions of a card or a list row. */
    compact: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val colors = buttonColors(variant)
    val fill = if (hovered && enabled) colors.hovered else colors.rest
    val bordered =
        colors.border?.let { Modifier.border(1.dp, it, IdeShapes.Medium) } ?: Modifier
    Box(
        modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .focusRing(focused)
            .background(fill, IdeShapes.Medium)
            .then(bordered)
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .then(buttonSize(compact)),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { IdeIcon(it, color = colors.content, size = 14.dp) }
            Label(
                text,
                color = colors.content,
                fontWeight = FontWeight.Medium,
                fontSize = if (compact) 12.sp else 13.sp,
            )
        }
    }
}

// Height of a compact button (shadcn/ui's `h-7`).
private val COMPACT_HEIGHT = 26.dp

private fun buttonSize(compact: Boolean): Modifier {
    val height = if (compact) COMPACT_HEIGHT else CONTROL_HEIGHT
    val horizontal = if (compact) 8.dp else 12.dp
    return Modifier.heightIn(min = height).padding(PaddingValues(horizontal = horizontal, vertical = 4.dp))
}

/** Small square ghost button (shadcn/ui's `size="icon"`) that highlights on hover. */
@Composable
fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .size(24.dp)
            .background(if (hovered) IdeColors.Hover else Color.Transparent, IdeShapes.Medium)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** shadcn/ui's badge: a small rounded label, [outline] or filled with a muted color. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = IdeColors.Text, outline: Boolean = false) {
    val shape = IdeShapes.Small
    val filled =
        if (outline) {
            Modifier.border(1.dp, IdeColors.Border, shape)
        } else {
            Modifier.background(IdeColors.Selection, shape)
        }
    Box(modifier.then(filled).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Label(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun VerticalDivider(modifier: Modifier = Modifier, color: Color = IdeColors.Border) {
    Box(modifier.width(1.dp).background(color))
}

/** Small popup text shown by a TooltipArea: shadcn/ui's tooltip, in the inverted colors. */
@Composable
fun Tooltip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(IdeColors.Accent, IdeShapes.Medium)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Label(text, color = IdeColors.OnAccent, fontSize = 12.sp)
    }
}

fun plural(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"
