package org.example.kotlineditor.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp

private val WHEEL_STEP = 48.dp

/**
 * The wheel scrolls [listState] horizontally, a plain vertical one as well as a horizontal one (tilt wheel, touchpad,
 * Shift+wheel). Rows of tabs use it so that, once they overflow, they scroll without the thin scrollbar.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.wheelScrollsHorizontally(listState: LazyListState): Modifier =
    onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
        event.changes.firstOrNull()?.let { change ->
            val delta = change.scrollDelta.let { if (it.x != 0f) it.x else it.y }
            if (delta != 0f) {
                listState.dispatchRawDelta(delta * WHEEL_STEP.toPx())
                change.consume()
            }
        }
    }
