package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.ProjectSession
import org.example.kotlineditor.model.RecentProjectsStore
import org.example.kotlineditor.model.SessionFile
import org.example.kotlineditor.model.SessionStore
import org.example.kotlineditor.model.pathName
import java.io.File

/** Recent projects persisted as one path per line in [file]. */
class FileRecentProjectsStore(private val file: File) : RecentProjectsStore {
    override suspend fun load(): List<String> =
        withContext(Dispatchers.IO) {
            runCatching { file.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        }

    override suspend fun save(paths: List<String>) {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(paths.joinToString("\n"))
        }
    }
}

/** Sessions stored as small tab-separated files, one per project, in [directory]. */
class FileSessionStore(private val directory: File) : SessionStore {
    override suspend fun load(projectPath: String): ProjectSession? =
        withContext(Dispatchers.IO) {
            runCatching { parse(fileFor(projectPath).readLines()) }.getOrNull()
        }

    override suspend fun save(projectPath: String, session: ProjectSession) {
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            fileFor(projectPath).writeText(
                buildString {
                    session.activeFile?.let { appendLine(listOf(ACTIVE, it).joinToString(SEPARATOR)) }
                    session.openFiles.forEach {
                        appendLine(listOf(OPEN, it.path, it.line, it.column).joinToString(SEPARATOR))
                    }
                },
            )
        }
    }

    private fun fileFor(projectPath: String) =
        File(
            directory,
            Integer.toHexString(projectPath.hashCode()) + "-" + pathName(projectPath).replace(UNSAFE, "_") + ".tsv",
        )

    private fun parse(lines: List<String>): ProjectSession {
        val fields = lines.map { it.split('\t') }
        val active = fields.firstOrNull { it.first() == ACTIVE }?.getOrNull(1)
        val open =
            fields.filter { it.first() == OPEN && it.size >= OPEN_FIELDS }.map {
                SessionFile(it[1], it[2].toInt(), it[LAST].toInt())
            }
        return ProjectSession(open, active)
    }

    private companion object {
        const val SEPARATOR = "\t"
        const val ACTIVE = "active"
        const val OPEN = "open"
        const val OPEN_FIELDS = 4
        const val LAST = 3
        val UNSAFE = Regex("""[^\w.-]""")
    }
}
