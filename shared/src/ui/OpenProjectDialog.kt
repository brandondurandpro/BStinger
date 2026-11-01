@file:Suppress("TooManyFunctions")

package org.example.kotlineditor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.parentPath
import org.example.kotlineditor.state.AppState
import java.io.IOException

private val LIST_HEIGHT = 320.dp
private val PICKER_ROW_HEIGHT = 30.dp
private const val OPEN_PROJECT_WIDTH = 640

private const val DOUBLE_CLICK_MILLIS = 400L

/**
 * The Open Project dialog, shown by the Open button of the welcome screen and by the project menu: a browser
 * of the directories of the server's disk, which the project lives on. It lists the child directories of the
 * shown directory, the user can type any path, and it opens the shown directory, or the highlighted one, as
 * the project.
 */
@Composable
internal fun OpenProjectDialog(app: AppState) {
    val onClose = app::closeProjectPicker
    val scope = rememberCoroutineScope()
    val browser = remember { DirectoryBrowser(app, scope) }
    LaunchedEffect(Unit) { browser.show(app.projectPickerStart()) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(browser) { runCatching { fieldFocus.requestFocus() } }
    var fieldFocused by remember { mutableStateOf(false) }

    PopupFrame(Tags.OPEN_PROJECT, onClose, width = OPEN_PROJECT_WIDTH) {
        Column(Modifier.pickerPanelKeys(browser, fieldFocused, onClose)) {
            DirectoryHeader(browser, fieldFocus, { fieldFocused = it }, onClose)
            browser.error?.let {
                Label(
                    it,
                    Modifier.padding(top = 6.dp).testTag(Tags.OPEN_PROJECT_ERROR),
                    color = IdeColors.Error,
                    fontSize = 12.sp,
                )
            }
            DirectoryList(browser)
            DirectoryFooter(browser, onClose)
        }
    }
}

@Composable
private fun DirectoryHeader(
    browser: DirectoryBrowser,
    fieldFocus: FocusRequester,
    setFieldFocused: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Label("Open Project", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Label(
        "Choose a project directory on the server.",
        Modifier.padding(top = 2.dp),
        color = IdeColors.TextSecondary,
        fontSize = 12.sp,
    )
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(browser::goUp, Modifier.testTag(Tags.OPEN_PROJECT_UP)) { ArrowIcon(up = true) }
        IdeTextField(
            browser.path,
            { browser.onPathEdited(it) },
            Modifier
                .weight(1f)
                .padding(start = 6.dp)
                .testTag(Tags.OPEN_PROJECT_PATH)
                .onFocusChanged { setFieldFocused(it.isFocused) }
                .focusRequester(fieldFocus)
                .fieldKeys({ browser.followPath() }, onClose),
            placeholder = "Directory path",
            onSubmit = browser::followPath,
        )
    }
}

@Composable
private fun DirectoryList(browser: DirectoryBrowser) {
    val listState = rememberLazyListState()
    LaunchedEffect(browser.selection) {
        browser.selection?.let { selection ->
            val index = browser.directories.indexOfFirst { it == selection }
            val visible = listState.layoutInfo.visibleItemsInfo
            if (index >= 0 && visible.none { it.index == index }) {
                listState.scrollToItem(index)
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(LIST_HEIGHT)) {
        when {
            browser.loading -> {
                LoadingDirectories()
            }

            browser.directories.isEmpty() -> {
                EmptyDirectories()
            }

            else -> {
                LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = listState) {
                    items(browser.directories, key = { it.path }) { entry ->
                        DirectoryRow(
                            entry,
                            selected = entry == browser.selection,
                            onSelect = { browser.pick(entry) },
                            onShowContent = { browser.showContent(entry) },
                        )
                    }
                }
                VerticalScrollbar(
                    rememberScrollbarAdapter(listState),
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun DirectoryFooter(browser: DirectoryBrowser, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
        IdeButton("Cancel", onClose, Modifier.testTag(Tags.OPEN_PROJECT_CANCEL))
        IdeButton(
            "Open",
            browser::openHighlighted,
            Modifier.padding(start = 8.dp).testTag(Tags.OPEN_PROJECT_OPEN),
            primary = true,
            enabled = browser.current != null && !browser.loading,
        )
    }
}

// One directory of the list: a single click selects it (the highlighted one opens as the project with Enter
// or the Open button); clicking the same row again right away (a double click) shows its content instead.
@Composable
private fun DirectoryRow(entry: FileEntry, selected: Boolean, onSelect: () -> Unit, onShowContent: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val previousClick = remember { PreviousClick() }
    val background =
        when {
            selected -> IdeColors.Selection
            hovered -> IdeColors.Hover
            else -> Color.Transparent
        }
    Row(
        Modifier
            .fillMaxWidth()
            .height(PICKER_ROW_HEIGHT)
            .background(background, RoundedCornerShape(4.dp))
            .testTag(ItemTags.pickerDirectory(entry.path))
            .padding(horizontal = 8.dp)
            .hoverable(interaction)
            .clickable(interaction, indication = null) {
                // Double click detection without delaying the single click (Compose's onDoubleClick delays it).
                if (previousClick.isDouble(entry.path, System.currentTimeMillis())) onShowContent() else onSelect()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolderIcon(Modifier.size(16.dp))
        Label(entry.name, Modifier.padding(start = 8.dp))
    }
}

/** Remembers the previous click of a row: the same one again within [DOUBLE_CLICK_MILLIS] is a double click. */
private class PreviousClick {
    private var path: String? = null
    private var time = 0L

    fun isDouble(path: String, now: Long): Boolean {
        val double = path == this.path && now - time < DOUBLE_CLICK_MILLIS
        this.path = if (double) null else path
        time = now
        return double
    }
}

@Composable
private fun LoadingDirectories() {
    Box(
        Modifier
            .fillMaxSize()
            .testTag(Tags.OPEN_PROJECT_LOADING),
        contentAlignment = Alignment.Center,
    ) {
        Label("Listing the directories…", color = IdeColors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun EmptyDirectories() {
    Box(
        Modifier
            .fillMaxSize()
            .testTag(Tags.OPEN_PROJECT_EMPTY),
        contentAlignment = Alignment.Center,
    ) {
        Label("No directories here", color = IdeColors.TextSecondary, fontSize = 12.sp)
    }
}

// Enter enters the directory at the path (the field's navigation), Escape closes the dialog.
private fun Modifier.fieldKeys(commit: () -> Unit, close: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val key = event.key
        when {
            event.type != KeyEventType.KeyDown -> {
                false
            }

            key == Key.Enter || key == Key.NumPadEnter -> {
                commit()
                true
            }

            key == Key.Escape -> {
                close()
                true
            }

            else -> {
                false
            }
        }
    }

// The keyboard of the dialog (preview, ahead of the focused path field): the arrows move the highlighted
// directory of the list (even while typing, like an autocomplete of IntelliJ), Escape closes; Enter opens the
// highlighted directory as the project, but stays with the path field while it owns the focus, committing it.
private fun Modifier.pickerPanelKeys(browser: DirectoryBrowser, fieldFocused: Boolean, close: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val key = event.key
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            when (key) {
                Key.DirectionDown -> {
                    browser.move(1)
                    true
                }

                Key.DirectionUp -> {
                    browser.move(-1)
                    true
                }

                Key.Enter, Key.NumPadEnter -> {
                    if (fieldFocused) {
                        false
                    } else {
                        browser.openHighlighted()
                        true
                    }
                }

                Key.Escape -> {
                    close()
                    true
                }

                else -> {
                    false
                }
            }
        }
    }

/**
 * State of the directory list of one Open Project dialog. Navigations run on [scope] over the network; only
 * the answer of the latest one applies, so on a slow or broken link a late answer never overrides a newer one.
 */
private class DirectoryBrowser(private val app: AppState, private val scope: CoroutineScope) {
    /** The directory shown, or null until the first one resolved. Opening a project also uses it. */
    var current: FileEntry? by mutableStateOf(null)
        private set

    /** The text of the path field: the shown directory, freely editable until its Enter commits it. */
    var path: String by mutableStateOf("")
        private set

    /** Whether a path is being typed: the user's Enter commits it, against a mid-flight answer showing its own. */
    var editing: Boolean by mutableStateOf(false)
        private set

    /** The directory highlighted in the list (the one that Enter or the Open button would open). */
    var selection: FileEntry? by mutableStateOf(null)

    /** Subdirectories of the shown directory, sorted as the server lists them. */
    var directories: List<FileEntry> by mutableStateOf(emptyList())
        private set

    var loading: Boolean by mutableStateOf(true)
        private set

    /** Why the last navigation failed, `null` when it succeeded or while it runs. */
    var error: String? by mutableStateOf(null)
        private set

    // Counted to let only the latest navigation apply.
    private var navigation = 0

    /** Shows the parent of the browsed directory (the Up button), one level back. */
    fun goUp() {
        current?.let { entry -> parentPath(entry.path)?.let(::show) }
    }

    /** Edits the path field (each keystroke); the user's Enter then commits the typed path. */
    fun onPathEdited(text: String) {
        path = text
        editing = true
    }

    /** Enters the directory at the path field (its Enter). */
    fun followPath() {
        editing = false
        show(path.trim().ifEmpty { "/" })
    }

    /** Shows what [directory] contains (its double click): its path replaces the shown directory. */
    fun showContent(directory: FileEntry) = show(directory.path)

    /** Highlights [entry] (its click), which ends any path being typed: Enter serves the list then. */
    fun pick(entry: FileEntry) {
        editing = false
        selection = entry
    }

    /** Moves the highlight by [delta] among the directories shown (the ArrowUp and ArrowDown keys). */
    fun move(delta: Int) {
        if (directories.isEmpty()) return
        val index = (directories.indexOf(selection) + delta).coerceIn(0, directories.lastIndex)
        pick(directories[index])
    }

    /** Opens the highlighted directory as the project, or the browsed one when nothing is highlighted. */
    fun openHighlighted() {
        (selection ?: current)?.let { app.openFromProjectPicker(it.path) }
    }

    /**
     * Shows the directory at [path] and lists the directories it contains. The navigation starts loading right
     * away, before its coroutine runs: an Open pressed in between must not open the directory shown before.
     */
    fun show(path: String) {
        val request = ++navigation
        loading = true
        error = null
        scope.launch { navigate(path, request) }
    }

    private suspend fun navigate(path: String, request: Int) {
        val listing =
            try {
                listDirectory(path)
            } catch (expected: IOException) {
                if (request == navigation) fail("Cannot reach the server: ${expected.message}")
                null
            }
        if (listing == null && request == navigation && error == null) fail("Directory not found: $path")
        if (listing != null && request == navigation) show(listing)
    }

    // Applies the listing that won the navigation counter.
    private fun show(listing: Pair<FileEntry, List<FileEntry>>) {
        current = listing.first
        if (!editing) this.path = listing.first.path
        directories = listing.second
        selection = null
        loading = false
        app.rememberProjectPickerDirectory(listing.first.path)
    }

    // Resolves [path] on the server and lists its directories, or `null` when there is no such directory.
    private suspend fun listDirectory(path: String): Pair<FileEntry, List<FileEntry>>? {
        val directory =
            app.resolvePickerDirectory(path)
                ?: return null
        return directory to app.pickerSubdirectories(directory)
    }

    private fun fail(message: String) {
        loading = false
        error = message
    }
}
