package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Single-line text field styled like IntelliJ's inputs. */
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
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        cursorBrush = SolidColor(IdeColors.Caret),
        textStyle =
        if (monospace) {
            LocalCodeStyle.current.copy(color = IdeColors.Text)
        } else {
            TextStyle(color = IdeColors.Text, fontSize = 13.sp)
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        decorationBox = { inner ->
            Row(
                Modifier
                    .background(IdeColors.Input, RoundedCornerShape(4.dp))
                    .border(1.dp, IdeColors.InputBorder, RoundedCornerShape(4.dp))
                    .heightIn(min = 28.dp)
                    .padding(horizontal = 8.dp),
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

@Composable
fun IdeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background =
        when {
            !enabled -> Color.Transparent
            primary && hovered -> IdeColors.AccentHover
            primary -> IdeColors.Accent
            hovered -> IdeColors.Hover
            else -> Color.Transparent
        }
    Box(
        modifier
            .background(background, RoundedCornerShape(4.dp))
            .then(if (primary) Modifier else Modifier.border(1.dp, IdeColors.InputBorder, RoundedCornerShape(4.dp)))
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .heightIn(min = 28.dp)
            .padding(PaddingValues(horizontal = 14.dp, vertical = 5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Label(
            text,
            color =
            when {
                !enabled -> IdeColors.LineNumber
                primary -> Color.White
                else -> IdeColors.Text
            },
        )
    }
}

/** Small square icon button that highlights on hover. */
@Composable
fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .size(22.dp)
            .background(if (hovered) IdeColors.Hover else Color.Transparent, RoundedCornerShape(4.dp))
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun VerticalDivider(modifier: Modifier = Modifier, color: Color = IdeColors.Border) {
    Box(modifier.width(1.dp).background(color))
}

/** Small popup text shown by a TooltipArea. */
@Composable
fun Tooltip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(IdeColors.Popup, RoundedCornerShape(4.dp))
            .border(1.dp, IdeColors.Separator, RoundedCornerShape(4.dp))
            .padding(6.dp),
    ) {
        Label(text, fontSize = 12.sp)
    }
}
