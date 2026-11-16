package org.example.kotlineditor.model

/** The files a project has open when it is closed, saved to reopen it as it was. */
data class ProjectSession(
    /** Open files, in tab order. */
    val openFiles: List<SessionFile>,
    /** Path of the active file. */
    val activeFile: String?,
)

/** One open file of a [ProjectSession]. */
data class SessionFile(
    /** Path of the file. */
    val path: String,
    /** Caret line. */
    val line: Int,
    /** Caret column. */
    val column: Int,
)

/** Sessions of the projects, keyed by project path. */
interface SessionStore {
    /** The session of [projectPath], if any. */
    suspend fun load(projectPath: String): ProjectSession?

    /** Stores the session of [projectPath]. */
    suspend fun save(projectPath: String, session: ProjectSession)
}

/** [SessionStore] kept in memory. */
class InMemorySessionStore : SessionStore {
    /** The stored sessions. */
    val sessions = HashMap<String, ProjectSession>()

    override suspend fun load(projectPath: String) = sessions[projectPath]

    override suspend fun save(projectPath: String, session: ProjectSession) {
        sessions[projectPath] = session
    }
}
