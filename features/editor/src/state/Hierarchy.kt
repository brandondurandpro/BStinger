package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.HierarchyDirection
import org.example.kotlineditor.model.HierarchyItem
import org.example.kotlineditor.model.HierarchyKind
import org.example.kotlineditor.model.HierarchyResult

// The call hierarchy (Ctrl+Alt+H) and the type hierarchy (Ctrl+H): a tree whose nodes are asked to the language
// server when they are expanded.

private const val CHILDREN_TIMEOUT_MS = 5_000L

/** A function or a type of the tree, at [depth] (the root is at 0). */
@Stable
class HierarchyNode(val item: HierarchyItem, val depth: Int) {
    /** Its children once the server gave them; null before. */
    var children: List<HierarchyNode>? by mutableStateOf(null)
        internal set

    /** Its children are shown. */
    var expanded by mutableStateOf(false)
        internal set

    /** The server is asked for its children. */
    var loading by mutableStateOf(false)
        internal set

    /** The server could not answer: expanding it again asks again. */
    var failed by mutableStateOf(false)
        internal set
}

/** The hierarchy shown over [document], started in it at [path], on side [direction] of [root]. */
@Stable
class HierarchyPopup internal constructor(
    val document: Document,
    val path: String,
    val kind: HierarchyKind,
    val anchor: TextPos,
    direction: HierarchyDirection,
    private val rootItem: HierarchyItem,
) {
    /** Callers or callees, supertypes or subtypes. */
    var direction by mutableStateOf(direction)
        internal set

    /** The function or type the hierarchy started from. */
    var root by mutableStateOf(HierarchyNode(rootItem, 0))
        internal set

    /** The row chosen with the arrows or a click. */
    var selected by mutableIntStateOf(0)
        internal set

    /** The rows shown: the root, then the children of the expanded nodes, depth first. */
    val rows: List<HierarchyNode> get() = buildList { addRows(root) }

    private fun MutableList<HierarchyNode>.addRows(node: HierarchyNode) {
        add(node)
        val shown =
            node.children
                .orEmpty()
                .takeIf { node.expanded }
                .orEmpty()
        shown.forEach { addRows(it) }
    }

    internal fun restart(side: HierarchyDirection) {
        direction = side
        root = HierarchyNode(rootItem, 0)
        selected = 0
    }
}

/** The title of the hierarchy: what it shows of what. */
val HierarchyPopup.title: String
    get() =
        when (direction) {
            HierarchyDirection.Callers -> "Callers of ${root.item.name}"
            HierarchyDirection.Callees -> "Callees of ${root.item.name}"
            HierarchyDirection.Supertypes -> "Supertypes of ${root.item.name}"
            HierarchyDirection.Subtypes -> "Subtypes of ${root.item.name}"
        }

/** Ctrl+Alt+H ([HierarchyKind.Calls]) or Ctrl+H ([HierarchyKind.Types]): the hierarchy of the symbol at the caret. */
fun CodeInsightState.showHierarchy(path: String, document: Document, kind: HierarchyKind) {
    dismissHover()
    val caret = document.caret
    val word = wordRange(document, caret)
    val anchor = word?.let { TextPos(it.startLine, it.startColumn) } ?: caret
    scope.launch {
        val version = document.version
        val result = askAt(path, document, caret) { servers, at -> servers.prepareHierarchy(at, kind) }
        val answer = result ?: HierarchyResult.NotReady
        val root = answer.items.firstOrNull()
        val message = hierarchyMessage(answer, kind)
        when {
            document.version != version -> Unit
            message != null || root == null -> notify(document, anchor, message.orEmpty())
            else -> openHierarchy(HierarchyPopup(document, path, kind, anchor, sideOf(kind), root))
        }
    }
}

private fun hierarchyMessage(result: HierarchyResult, kind: HierarchyKind): String? {
    val calls = kind == HierarchyKind.Calls
    return when {
        result.status == FeatureStatus.NotReady -> NOT_READY
        result.status == FeatureStatus.Unsupported && calls -> "The language server has no call hierarchy"
        result.status == FeatureStatus.Unsupported -> "The language server has no type hierarchy"
        result.items.isEmpty() && calls -> "No function or method at the caret"
        result.items.isEmpty() -> "No class or interface at the caret"
        else -> null
    }
}

// Callers first, like IntelliJ; subtypes first, as the type hierarchy shows what derives from the class.
private fun sideOf(kind: HierarchyKind) =
    if (kind == HierarchyKind.Calls) HierarchyDirection.Callers else HierarchyDirection.Subtypes

private fun CodeInsightState.openHierarchy(popup: HierarchyPopup) {
    hierarchy = popup
    expand(popup, popup.root)
}

/** The button of the title: callers and callees, or supertypes and subtypes, the tree starting again. */
fun CodeInsightState.switchHierarchySide() {
    val popup = hierarchy ?: return
    val other =
        when (popup.direction) {
            HierarchyDirection.Callers -> HierarchyDirection.Callees
            HierarchyDirection.Callees -> HierarchyDirection.Callers
            HierarchyDirection.Supertypes -> HierarchyDirection.Subtypes
            HierarchyDirection.Subtypes -> HierarchyDirection.Supertypes
        }
    popup.restart(other)
    expand(popup, popup.root)
}

/** Shows the children of [node], asking the server for them the first time (or after it failed). */
fun CodeInsightState.expand(popup: HierarchyPopup, node: HierarchyNode) {
    node.expanded = true
    val known = node.children != null
    if (known || node.loading) return
    node.loading = true
    node.failed = false
    val direction = popup.direction
    scope.launch {
        // The server gets the document first (a restarted one has none); out of reach, the row says so in time.
        val result =
            withTimeoutOrNull(CHILDREN_TIMEOUT_MS) {
                askVersion(popup.path, popup.document) { servers, _ ->
                    servers.hierarchyChildren(popup.path, node.item, direction)
                }
            }
        val ready = result?.takeIf { it.status == FeatureStatus.Ready }
        node.loading = false
        node.failed = ready == null
        node.children = ready?.items?.map { HierarchyNode(it, node.depth + 1) }
    }
}
