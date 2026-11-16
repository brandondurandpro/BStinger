package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.FILE_CHANGED_MESSAGE
import org.example.kotlineditor.model.FileChangedException
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FileVersion
import org.example.kotlineditor.model.ProjectFileSystem
import org.example.kotlineditor.model.pathName

// JSON of files and their content, shared by client and server so both sides always agree.

private const val TEXT = "text"
private const val BINARY = "binary"
private const val TOO_LARGE = "tooLarge"
private const val ERROR = "error"
private const val VERSION = "version"

private const val DIRECTORY = "directory"
private const val ENTRY = "entry"
private const val FILE = "file"
private const val FOLD = "fold"
private const val NAME = "name"
private const val PATH = "path"

/** An entry: only the path and the kind travel, the name is derived from the path. */
fun FileEntry.toJson(): JsonObject =
    buildJsonObject {
        put("path", path)
        put("isDirectory", isDirectory)
        if (label != null) put("label", label)
    }

/** An optional entry, JSON null when absent. */
fun FileEntry?.toJsonOrNull(): JsonElement = this?.toJson() ?: JsonNull

/** Reads an entry written by [FileEntry.toJson]. */
fun JsonElement.entry(): FileEntry {
    val fields = jsonObject
    val path = fields.string("path")
    val label = fields["label"]?.jsonPrimitive?.contentOrNull
    return FileEntry(path, pathName(path), fields.bool("isDirectory"), label)
}

/** Reads an optional entry. */
fun JsonElement.entryOrNull(): FileEntry? = if (this == JsonNull) null else entry()

/** A list of entries. */
fun List<FileEntry>.toJson(): JsonElement = JsonArray(map { it.toJson() })

/** Reads a list of entries. */
fun JsonElement.entries(): List<FileEntry> = jsonArray.map { it.entry() }

/** A file content; text is sent as lines, already split by the server. */
fun FileContent.toJson(): JsonObject {
    val content = this
    val version = content.version
    return buildJsonObject {
        when (content) {
            is FileContent.Text -> {
                put("type", TEXT)
                put("lineSeparator", content.lineSeparator)
                put("lines", content.lines.toJson())
            }

            is FileContent.Binary -> {
                put("type", BINARY)
            }

            is FileContent.TooLarge -> {
                put("type", TOO_LARGE)
                put("size", content.size)
            }

            is FileContent.Error -> {
                put("type", ERROR)
                put("message", content.message)
            }
        }
        version?.let { put(VERSION, it.toJson()) }
    }
}

/** Reads a file content. */
fun JsonElement.content(): FileContent {
    val fields = jsonObject
    val version = fields.get(VERSION)?.versionOrNull()
    return when (fields.string("type")) {
        TEXT -> FileContent.Text(fields.field("lines").strings(), fields.string("lineSeparator"), version)
        BINARY -> FileContent.Binary(version)
        TOO_LARGE -> FileContent.TooLarge(fields.long("size"))
        else -> FileContent.Error(fields.string("message"))
    }
}

/** A list of strings. */
fun List<String>.toJson(): JsonArray = JsonArray(map(::JsonPrimitive))

/** Reads a list of strings. */
fun JsonElement.strings(): List<String> = jsonArray.map { it.string() }

/** [ProjectFileSystem] of the server. */
class RemoteFileSystem(private val client: RpcClient) : ProjectFileSystem {
    override suspend fun resolveDirectory(path: String): FileEntry? =
        client.call(Method.ResolveDirectory) { put(PATH, path) }.entryOrNull()

    override suspend fun list(directory: FileEntry): List<FileEntry> =
        client.call(Method.ListDirectory) { put(DIRECTORY, directory.toJson()) }.entries()

    override suspend fun listFolded(directory: FileEntry): List<FileEntry> {
        val answer =
            client.call(Method.ListDirectory) {
                put(DIRECTORY, directory.toJson())
                put(FOLD, true)
            }
        return answer.entries()
    }

    override suspend fun read(file: FileEntry): FileContent =
        client.call(Method.Read) { put(FILE, file.toJson()) }.content()

    override suspend fun fileAt(path: String): FileEntry? = client.call(Method.FileAt) { put(PATH, path) }.entryOrNull()

    override suspend fun write(file: FileEntry, text: String, expected: FileVersion?): FileVersion? =
        try {
            val result =
                client.call(Method.Write) {
                    put(FILE, file.toJson())
                    put("text", text)
                    if (expected != null) put(VERSION, expected.toJson())
                }
            result.versionOrNull()
        } catch (failure: RemoteException) {
            // The server's answer of a refused save names a known failure: the client sees it as what it is.
            val message = failure.message
            throw if (message == FILE_CHANGED_MESSAGE) FileChangedException(message) else failure
        }

    override suspend fun createFile(directory: FileEntry, name: String): FileEntry {
        val result =
            client.call(Method.CreateFile) {
                put(DIRECTORY, directory.toJson())
                put(NAME, name)
            }
        return result.entry()
    }

    override suspend fun createDirectory(directory: FileEntry, name: String): FileEntry {
        val result =
            client.call(Method.CreateDirectory) {
                put(DIRECTORY, directory.toJson())
                put(NAME, name)
            }
        return result.entry()
    }

    override suspend fun rename(entry: FileEntry, newName: String): FileEntry {
        val result =
            client.call(Method.Rename) {
                put(ENTRY, entry.toJson())
                put("newName", newName)
            }
        return result.entry()
    }

    override suspend fun delete(entry: FileEntry) {
        client.call(Method.Delete) { put(ENTRY, entry.toJson()) }
    }
}
