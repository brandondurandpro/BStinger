package org.example.kotlineditor.state

// Tab navigation and split actions.

fun EditorState.closeOthers(tab: EditorTab) {
    val pane = paneOf(tab) ?: return
    pane.tabs.filter { it != tab }.forEach {
        save(it)
        pane.tabs.remove(it)
        release(it)
    }
    activate(tab)
}

fun EditorState.selectNext() = selectRelative(1)

fun EditorState.selectPrevious() = selectRelative(-1)

private fun EditorState.selectRelative(delta: Int) {
    val current = activeTab
    if (current != null && tabs.size > 1) activate(tabs[(tabs.indexOf(current) + delta).mod(tabs.size)])
}

fun EditorState.saveAll() = allTabs.forEach { save(it) }

/** "Split Right": a second pane showing the active file (sharing its document). */
fun EditorState.splitRight() {
    val tab = activeTab
    if (tab != null && !isSplit) {
        val pane = EditorPane()
        panes.add(pane)
        openIn(pane, tab.entry)
    }
}

/** "Unsplit": moves the tabs of the other pane into the active one. */
fun EditorState.unsplit() {
    val keep = activePane
    val active = keep.activeTab
    for (other in panes.filter { it != keep }) {
        for (tab in other.tabs) {
            if (keep.tabs.any { it.entry.path == tab.entry.path }) release(tab) else keep.tabs.add(tab)
        }
        panes.remove(other)
    }
    keep.activeTab = active ?: keep.tabs.maxByOrNull { it.lastActivated }
}
