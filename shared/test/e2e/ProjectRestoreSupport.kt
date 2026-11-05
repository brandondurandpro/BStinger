package org.example.kotlineditor.e2e

import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CompletableDeferred
import org.example.kotlineditor.model.InMemorySessionStore
import org.example.kotlineditor.model.ProjectSession
import org.example.kotlineditor.model.SessionFile
import org.example.kotlineditor.model.SessionStore
import org.example.kotlineditor.state.Screen

/** What the project window shows of a restored session: its tabs, the active one and the unfolded directories. */
data class RestoreView(val tabs: List<String>, val active: String?, val unfolded: Set<String>)

/**
 * Records every [RestoreView] the project window goes through, from the moment it is shown: a state change applied
 * (one frame at most) is recorded, so a tab or a directory appearing on its own shows as a view of its own.
 */
class RestoreRecorder(private val app: E2eApp, private val project: SampleProject) : AutoCloseable {
    private val recorded = mutableListOf<RestoreView>()
    private val handle = Snapshot.registerApplyObserver { _, _ -> record() }

    /** The views recorded, each change once. */
    val views: List<RestoreView>
        get() = synchronized(recorded) { recorded.toList() }

    private fun record() {
        val screen = app.state.session.screen as? Screen.Project ?: return
        val shown = screen.project
        val tabs = shown.editor.tabs.map { relative(it.entry.path) }
        val active =
            shown.editor.activeTab
                ?.entry
                ?.path
                ?.let(::relative)
        val unfolded =
            shown.tree.rows
                .filter { it.expanded && it.depth > 0 }
                .map { relative(it.entry.path) }
                .toSet()
        val view = RestoreView(tabs, active, unfolded)
        synchronized(recorded) { if (recorded.lastOrNull() != view) recorded += view }
    }

    private fun relative(path: String): String =
        path
            .removePrefix(project.path)
            .trimStart('/', '\\')
            .replace('\\', '/')

    override fun close() = handle.dispose()
}

/** A session of [project] opening [files] (relative paths), [active] being the active one. */
fun sessionOf(project: SampleProject, files: List<String>, active: String?): ProjectSession {
    val absolute = { relative: String -> project.root.resolve(relative).toString() }
    return ProjectSession(files.map { SessionFile(absolute(it), 0, 0) }, active?.let(absolute))
}

/** A store whose sessions are only answered once [release]d: a server answering late. */
class LateSessionStore(private val stored: InMemorySessionStore = InMemorySessionStore()) : SessionStore {
    private val gate = CompletableDeferred<Unit>()

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun load(projectPath: String): ProjectSession? {
        gate.await()
        return stored.load(projectPath)
    }

    override suspend fun save(projectPath: String, session: ProjectSession) = stored.save(projectPath, session)
}
