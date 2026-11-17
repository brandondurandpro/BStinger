package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.FileSymbol
import org.example.kotlineditor.model.ProjectSession
import org.example.kotlineditor.model.RecentProjectsStore
import org.example.kotlineditor.model.ServerPlaces
import org.example.kotlineditor.model.SessionFile
import org.example.kotlineditor.model.SessionStore
import org.example.kotlineditor.syntax.Declaration
import org.example.kotlineditor.syntax.DeclarationKind

// JSON of the data of the project tree: the declarations (outline, go to symbol), and the session of a project.

private const val FILE = "file"
private const val LINE = "line"
private const val COLUMN = "column"

private const val PATHS = "paths"
private const val PROJECT = "project"

/** One declaration. */
fun Declaration.toJson(): JsonObject =
    buildJsonObject {
        put("name", name)
        put("kind", kind.name)
        put(LINE, line)
        put(COLUMN, column)
        put("depth", depth)
    }

/** Reads one declaration. */
fun JsonElement.declaration(): Declaration {
    val d = jsonObject
    return Declaration(
        d.string("name"),
        DeclarationKind.valueOf(d.string("kind")),
        d.int(LINE),
        d.int(COLUMN),
        d.int("depth"),
    )
}

/** The declarations of a project. */
fun List<FileSymbol>.symbolsJson(): JsonArray =
    JsonArray(
        map {
            buildJsonObject {
                put(FILE, it.entry.toJson())
                put("declaration", it.declaration.toJson())
            }
        },
    )

/** Reads the declarations of a project. */
fun JsonElement.symbols(): List<FileSymbol> =
    jsonArray.map {
        val symbol = it.jsonObject
        FileSymbol(symbol.field(FILE).entry(), symbol.field("declaration").declaration())
    }

/** An optional project session, JSON null when absent. */
fun ProjectSession?.toJson(): JsonElement =
    if (this == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("activeFile", activeFile)
            put(
                "openFiles",
                JsonArray(
                    openFiles.map {
                        buildJsonObject {
                            put("path", it.path)
                            put(LINE, it.line)
                            put(COLUMN, it.column)
                        }
                    },
                ),
            )
        }
    }

/** Reads an optional project session. */
fun JsonElement.session(): ProjectSession? {
    if (this == JsonNull) return null
    val session = jsonObject
    val files =
        session.field("openFiles").jsonArray.map {
            val file = it.jsonObject
            SessionFile(file.string("path"), file.int(LINE), file.int(COLUMN))
        }
    return ProjectSession(files, session.stringOrNull("activeFile"))
}

/** [ServerPlaces] of the server. */
class RemotePlaces(private val client: RpcClient) : ServerPlaces {
    override suspend fun home(): String? = client.call(Method.UserHome).stringOrNull()

    override suspend fun newConversationFolder(): String? = client.call(Method.ConversationFolder).stringOrNull()
}

/** [RecentProjectsStore] of the server. */
class RemoteRecentProjects(private val client: RpcClient) : RecentProjectsStore {
    override suspend fun load(): List<String> = client.call(Method.RecentLoad).strings()

    override suspend fun save(paths: List<String>) {
        client.call(Method.RecentSave) { put(PATHS, paths.toJson()) }
    }
}

/** [SessionStore] of the server. */
class RemoteSessions(private val client: RpcClient) : SessionStore {
    override suspend fun load(projectPath: String): ProjectSession? =
        client.call(Method.SessionLoad) { put(PROJECT, projectPath) }.session()

    override suspend fun save(projectPath: String, session: ProjectSession) {
        client.call(Method.SessionSave) {
            put(PROJECT, projectPath)
            put("session", session.toJson())
        }
    }
}
