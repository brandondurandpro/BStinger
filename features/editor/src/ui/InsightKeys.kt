package org.example.kotlineditor.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import org.example.kotlineditor.state.CodeInsightState
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.applyCodeAction
import org.example.kotlineditor.state.closeHierarchy
import org.example.kotlineditor.state.closeProblems
import org.example.kotlineditor.state.goToProblem
import org.example.kotlineditor.state.hierarchyLeft
import org.example.kotlineditor.state.hierarchyRight
import org.example.kotlineditor.state.moveHierarchySelection
import org.example.kotlineditor.state.moveProblemSelection
import org.example.kotlineditor.state.openHierarchyItem

/** The keys of the popups of code intelligence while one is shown, and Escape for whatever is shown. */
internal fun insightKey(event: KeyEvent, insight: CodeInsightState, document: Document): Boolean {
    val down = event.type == KeyEventType.KeyDown
    val plain = !event.isCtrlPressed && !event.isAltPressed
    val owner = if (down && plain) popupKeys(insight, document) else null
    return when {
        owner != null -> owner(event.key, insight)
        down && event.key == Key.Escape -> insight.escape()
        else -> false
    }
}

// The popup over [document] that owns the plain keys (arrows, Enter, Escape); other keys still edit the code below.
private fun popupKeys(insight: CodeInsightState, document: Document): ((Key, CodeInsightState) -> Boolean)? {
    val list = insight.locations?.document === document || insight.codeActions?.document === document
    return when {
        insight.hierarchy?.document === document -> ::hierarchyKey
        insight.problems?.document === document -> ::problemsKey
        list -> ::listKey
        else -> null
    }
}

// The problems list owns the arrows, Enter and Escape; other keys still edit the code below.
private fun problemsKey(key: Key, insight: CodeInsightState): Boolean {
    val enter = key == Key.Enter || key == Key.NumPadEnter
    when {
        key == Key.DirectionDown -> insight.moveProblemSelection(1)
        key == Key.DirectionUp -> insight.moveProblemSelection(-1)
        enter -> insight.problems?.let { insight.goToProblem(it.selected) }
        key == Key.Escape -> insight.closeProblems()
        else -> return false
    }
    return true
}

// The hierarchy owns the arrows, Enter and Escape.
private fun hierarchyKey(key: Key, insight: CodeInsightState): Boolean {
    val enter = key == Key.Enter || key == Key.NumPadEnter
    when {
        key == Key.DirectionDown -> insight.moveHierarchySelection(1)
        key == Key.DirectionUp -> insight.moveHierarchySelection(-1)
        key == Key.DirectionRight -> insight.hierarchyRight()
        key == Key.DirectionLeft -> insight.hierarchyLeft()
        enter -> insight.openHierarchyItem()
        key == Key.Escape -> insight.closeHierarchy()
        else -> return false
    }
    return true
}

private fun listKey(key: Key, insight: CodeInsightState): Boolean {
    val enter = key == Key.Enter || key == Key.NumPadEnter
    when {
        key == Key.DirectionDown -> insight.moveSelection(1)
        key == Key.DirectionUp -> insight.moveSelection(-1)
        enter && insight.locations != null -> insight.openLocation()
        enter -> insight.applyCodeAction()
        key == Key.Escape -> insight.escape()
        else -> return false
    }
    return true
}
