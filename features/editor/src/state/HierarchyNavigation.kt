package org.example.kotlineditor.state

// The keys and clicks of the call or type hierarchy.

/** A click on the arrow of a row: expands or collapses it. */
fun CodeInsightState.toggleHierarchyNode(index: Int) {
    val popup = hierarchy ?: return
    val node = popup.rows.getOrNull(index) ?: return
    popup.selected = index
    if (node.expanded) node.expanded = false else expand(popup, node)
}

/** Up and Down. */
fun CodeInsightState.moveHierarchySelection(delta: Int) {
    val popup = hierarchy ?: return
    popup.selected = (popup.selected + delta).mod(popup.rows.size)
}

/** Right: expands the row, or goes to its first child when it is expanded. */
fun CodeInsightState.hierarchyRight() {
    val popup = hierarchy ?: return
    val node = popup.rows.getOrNull(popup.selected) ?: return
    val hasChildren = node.expanded && !node.children.isNullOrEmpty()
    if (hasChildren) popup.selected++ else expand(popup, node)
}

/** Left: collapses the row, or goes to its parent when it is collapsed. */
fun CodeInsightState.hierarchyLeft() {
    val popup = hierarchy ?: return
    val rows = popup.rows
    val node = rows.getOrNull(popup.selected) ?: return
    if (node.expanded) {
        node.expanded = false
    } else {
        val parent = rows.subList(0, popup.selected).indexOfLast { it.depth < node.depth }
        if (parent >= 0) popup.selected = parent
    }
}

/** Enter or a double click: opens the place of the row (a call for a caller, the declaration otherwise). */
fun CodeInsightState.openHierarchyItem(index: Int? = null) {
    val popup = hierarchy ?: return
    val node = popup.rows.getOrNull(index ?: popup.selected) ?: return
    hierarchy = null
    val location = node.item.location
    host.navigate(location.path, TextPos(location.range.startLine, location.range.startColumn))
}

/** Escape, or a click beside the tree. */
fun CodeInsightState.closeHierarchy() {
    hierarchy = null
}
