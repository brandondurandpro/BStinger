package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.LocationsResult
import org.example.kotlineditor.model.NavigationKind
import org.example.kotlineditor.model.SourceLocation
import org.example.kotlineditor.model.TextRange
import org.example.kotlineditor.syntax.treesitter.Snippet
import org.example.kotlineditor.telemetry.Attribute
import org.example.kotlineditor.telemetry.Attributes
import org.example.kotlineditor.telemetry.ClientTelemetry
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.measureToFrame

/** Where a navigation was asked from, and what it looks for. */
private class NavigationRequest(
    val path: String,
    val document: Document,
    val pos: TextPos,
    val kind: NavigationKind,
    val word: TextRange?,
) {
    val anchor: TextPos get() = word?.let { TextPos(it.startLine, it.startColumn) } ?: pos
    val name: String? get() = word?.let { document.lines[it.startLine].substring(it.startColumn, it.endColumn) }
}

/**
 * Go to declaration, type declaration and implementation, usages, and the list of places to choose from. [dismissHover]
 * closes the documentation, which a navigation replaces.
 */
@Stable
internal class NavigationInsight(
    private val scope: CoroutineScope,
    private val requests: InsightRequests,
    private val notices: InsightNotices,
    private val host: InsightHost,
    private val dismissHover: () -> Unit,
) {
    /** The list of places shown, null when none. */
    var locations: LocationsPopup? by mutableStateOf(null)

    private var previewColoring: Job? = null

    /** Goes to what [kind] looks for at [pos]; several places are listed to choose from. */
    fun navigate(path: String, document: Document, pos: TextPos, kind: NavigationKind) {
        dismissHover()
        val request = NavigationRequest(path, document, pos, kind, wordRange(document, pos))
        scope.launch {
            // From the shortcut to the frame that shows the declaration, or the list of the choices.
            ClientTelemetry.measureToFrame(Operation.EditorNavigation, attributesOf = ::locationsOf) {
                val version = document.version
                val result =
                    notices.whileIndexing(document, request.anchor) {
                        requests.askAt(path, document, pos) { servers, at -> servers.navigate(at, kind) }
                    } ?: LocationsResult.NotReady
                if (document.version == version) navigated(request, result)
                result
            }
        }
    }

    private fun locationsOf(result: LocationsResult): Attributes {
        val found =
            when {
                result.status == FeatureStatus.NotReady -> "partial"
                result.locations.isEmpty() -> "empty"
                else -> "found"
            }
        return Attributes.of(Attribute.Result to found)
    }

    private suspend fun navigated(request: NavigationRequest, result: LocationsResult) {
        val document = request.document
        val anchor = request.anchor
        val ready = result.status == FeatureStatus.Ready
        // Ctrl+B without a language server: by name, as without one.
        val byName = !ready && request.kind == NavigationKind.Definition
        when {
            byName -> {
                val found = host.declarationByName(request.pos)
                if (!found) notices.notify(document, anchor, emptyMessage(request.kind))
            }

            result.status == FeatureStatus.Unsupported -> {
                notices.notify(document, anchor, "The language server cannot find ${what(request.kind)}")
            }

            result.status == FeatureStatus.NotReady -> {
                notices.notify(document, anchor, NOT_READY)
            }

            result.locations.isEmpty() -> {
                notices.notify(document, anchor, emptyMessage(request.kind))
            }

            else -> {
                arrived(request, result.locations)
            }
        }
    }

    private fun arrived(request: NavigationRequest, found: List<SourceLocation>) {
        val name = request.name
        val single = found.singleOrNull()
        val landing = single?.let { landingOf(it, name) }
        val here = single != null && sameFile(single.path, request.path)
        val onItself = here && landing != null && isAt(request.pos, landing, name)
        when {
            onItself && request.kind == NavigationKind.Definition -> {
                showUsages(request.path, request.document, request.pos)
            }

            single != null && landing != null -> {
                host.navigate(single.path, landing)
            }

            else -> {
                showLocations(listTitle(request.kind, name), found, request.document, request.anchor)
            }
        }
    }

    // A definition range may cover the whole declaration (phpactor): the caret goes to the name inside it.
    private fun landingOf(location: SourceLocation, name: String?): TextPos {
        val range = location.range
        val preview = location.preview
        val from = range.startColumn.coerceAtMost(preview?.length ?: 0)
        val column = if (name != null && preview != null) preview.indexOf(name, from) else -1
        return if (column >= 0) TextPos(range.startLine, column) else TextPos(range.startLine, range.startColumn)
    }

    private fun isAt(pos: TextPos, landing: TextPos, name: String?): Boolean {
        val length = name?.length ?: 0
        return pos.line == landing.line && pos.column in landing.column..landing.column + length
    }

    /** The usages of the symbol at [pos]; one usage is opened directly. */
    fun showUsages(path: String, document: Document, pos: TextPos) {
        dismissHover()
        val word = wordRange(document, pos)
        val anchor = word?.let { TextPos(it.startLine, it.startColumn) } ?: pos
        val name = word?.let { document.lines[it.startLine].substring(it.startColumn, it.endColumn) }
        scope.launch {
            val version = document.version
            val result =
                notices.whileIndexing(document, anchor) {
                    requests.askAt(path, document, pos) { servers, at -> servers.references(at, false) }
                } ?: LocationsResult.NotReady
            val message =
                when {
                    result.status == FeatureStatus.NotReady -> NOT_READY
                    result.status == FeatureStatus.Unsupported -> "The language server cannot find usages"
                    result.locations.isEmpty() -> "No usages found"
                    else -> null
                }
            when {
                document.version != version -> Unit
                message != null -> notices.notify(document, anchor, message)
                else -> showLocations(usagesTitle(name, result), result.locations, document, anchor)
            }
        }
    }

    private fun usagesTitle(name: String?, result: LocationsResult): String {
        val count = result.locations.size
        val more = if (result.truncated) "+" else ""
        val usages = if (count == 1) "1 usage" else "$count$more usages"
        return if (name != null) "Usages of $name  $usages" else usages
    }

    /** Lists [found] to choose from, their previews colored once the highlighter has read them. */
    fun showLocations(title: String, found: List<SourceLocation>, document: Document, anchor: TextPos) {
        val names = found.map { host.relativePath(it.path) }
        val popup = LocationsPopup(title, found, document, anchor, names)
        locations = popup
        previewColoring?.cancel()
        val snippets = popup.previews.mapIndexed { index, preview -> Snippet(names[index], preview) }
        previewColoring = scope.launch { popup.previewColors = host.colorsOf(snippets) }
    }

    /** Opens the place [index] of the list, or the selected one. */
    fun openLocation(index: Int?) {
        val popup = locations ?: return
        val location = popup.locations.getOrNull(index ?: popup.selected) ?: return
        locations = null
        host.navigate(location.path, TextPos(location.range.startLine, location.range.startColumn))
    }
}

private fun what(kind: NavigationKind) =
    when (kind) {
        NavigationKind.Definition -> "declarations"
        NavigationKind.TypeDefinition -> "type declarations"
        NavigationKind.Implementation -> "implementations"
    }

private fun emptyMessage(kind: NavigationKind) =
    when (kind) {
        NavigationKind.Definition -> "Cannot find declaration to go to"
        NavigationKind.TypeDefinition -> "Cannot find type declaration to go to"
        NavigationKind.Implementation -> "No implementations found"
    }

private fun listTitle(kind: NavigationKind, name: String?): String {
    val of = name?.let { " of $it" }.orEmpty()
    return when (kind) {
        NavigationKind.Definition -> "Choose declaration$of"
        NavigationKind.TypeDefinition -> "Choose type declaration$of"
        NavigationKind.Implementation -> "Choose implementation$of"
    }
}
