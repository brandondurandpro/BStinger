package org.example.kotlineditor.rpc

import kotlinx.serialization.json.put
import org.example.kotlineditor.model.CompletionResult
import org.example.kotlineditor.model.LanguageServerChoice
import org.example.kotlineditor.model.LanguageServers
import org.example.kotlineditor.model.LanguageServersStatus
import org.example.kotlineditor.model.TextChange

private const val ID = "id"
private const val LANGUAGE = "language"
private const val PROJECT = "project"
private const val PATH = "path"
private const val VERSION = "version"

/** [LanguageServers] of the server. */
@Suppress("TooManyFunctions")
class RemoteLanguageServers(private val client: RpcClient) : LanguageServers {
    override suspend fun status(since: Long): LanguageServersStatus =
        client.call(Method.LspStatus) { put("since", since) }.languageServersStatus()

    override suspend fun use(project: String, files: List<String>): List<String> {
        val unchosen =
            client.call(Method.LspUse) {
                put(PROJECT, project)
                put("files", files.toJson())
            }
        return unchosen.strings()
    }

    override suspend fun restart(id: String, generation: Long) {
        client.call(Method.LspRestart) {
            put(ID, id)
            put("generation", generation)
        }
    }

    override suspend fun stop(id: String) {
        client.call(Method.LspStop) { put(ID, id) }
    }

    override suspend fun start(id: String) {
        client.call(Method.LspStart) { put(ID, id) }
    }

    override suspend fun log(id: String): List<String> = client.call(Method.LspLog) { put(ID, id) }.strings()

    override suspend fun choice(language: String, project: String): LanguageServerChoice {
        val choice =
            client.call(Method.LspChoice) {
                put(LANGUAGE, language)
                put(PROJECT, project)
            }
        return choice.languageServerChoice()
    }

    override suspend fun choose(language: String, project: String, candidate: String) {
        client.call(Method.LspChoose) {
            put(LANGUAGE, language)
            put(PROJECT, project)
            put("candidate", candidate)
        }
    }

    override suspend fun openDocument(project: String, path: String, version: Int, text: String) {
        client.call(Method.DocOpen) {
            put(PROJECT, project)
            put(PATH, path)
            put(VERSION, version)
            put("text", text)
        }
    }

    override suspend fun changeDocument(path: String, version: Int, changes: List<TextChange>) {
        client.call(Method.DocChange) {
            put(PATH, path)
            put(VERSION, version)
            put("changes", changes.textChangesJson())
        }
    }

    override suspend fun closeDocument(path: String) {
        client.call(Method.DocClose) { put(PATH, path) }
    }

    override suspend fun complete(path: String, version: Int, line: Int, column: Int): CompletionResult {
        val result =
            client.call(Method.LspCompletion) {
                put(PATH, path)
                put(VERSION, version)
                put("line", line)
                put("column", column)
            }
        return result.completionResult()
    }
}
