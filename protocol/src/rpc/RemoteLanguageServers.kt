package org.example.kotlineditor.rpc

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.CodeActionsResult
import org.example.kotlineditor.model.CompletionResult
import org.example.kotlineditor.model.DiagnosticsSnapshot
import org.example.kotlineditor.model.DocumentPosition
import org.example.kotlineditor.model.EditResult
import org.example.kotlineditor.model.FormatOptions
import org.example.kotlineditor.model.HoverResult
import org.example.kotlineditor.model.LanguageServerChoice
import org.example.kotlineditor.model.LanguageServers
import org.example.kotlineditor.model.LanguageServersStatus
import org.example.kotlineditor.model.LocationsResult
import org.example.kotlineditor.model.NavigationKind
import org.example.kotlineditor.model.OutlineResult
import org.example.kotlineditor.model.RenameTarget
import org.example.kotlineditor.model.SignatureHelpResult
import org.example.kotlineditor.model.SymbolHighlight
import org.example.kotlineditor.model.TextChange
import org.example.kotlineditor.model.TextRange
import org.example.kotlineditor.model.UsageCountsResult
import org.example.kotlineditor.model.WorkspaceSymbolsResult

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

    override suspend fun resolveCompletion(token: String, index: Int): String? {
        val result =
            client.call(Method.LspResolveCompletion) {
                put("token", token)
                put("index", index)
            }
        return result.jsonObject.stringOrNull("documentation")
    }

    override suspend fun hover(at: DocumentPosition): HoverResult =
        client.call(Method.LspHover) { putPosition(at) }.hoverResult()

    override suspend fun navigate(at: DocumentPosition, kind: NavigationKind): LocationsResult {
        val result =
            client.call(Method.LspNavigate) {
                putPosition(at)
                put("kind", kind.name)
            }
        return result.locationsResult()
    }

    override suspend fun references(at: DocumentPosition, includeDeclaration: Boolean): LocationsResult {
        val result =
            client.call(Method.LspReferences) {
                putPosition(at)
                put("includeDeclaration", includeDeclaration)
            }
        return result.locationsResult()
    }

    override suspend fun highlights(at: DocumentPosition): List<SymbolHighlight> =
        client.call(Method.LspHighlights) { putPosition(at) }.symbolHighlights()

    override suspend fun signatureHelp(at: DocumentPosition): SignatureHelpResult =
        client.call(Method.LspSignatureHelp) { putPosition(at) }.signatureHelpResult()

    override suspend fun outline(path: String, version: Int): OutlineResult {
        val result =
            client.call(Method.LspOutline) {
                put(PATH, path)
                put(VERSION, version)
            }
        return result.outlineResult()
    }

    override suspend fun workspaceSymbols(query: String): WorkspaceSymbolsResult =
        client.call(Method.LspWorkspaceSymbols) { put("query", query) }.workspaceSymbolsResult()

    override suspend fun usageCounts(path: String, version: Int): UsageCountsResult {
        val result =
            client.call(Method.LspUsageCounts) {
                put(PATH, path)
                put(VERSION, version)
            }
        return result.usageCountsResult()
    }

    override suspend fun diagnostics(since: Long): DiagnosticsSnapshot =
        client.call(Method.LspDiagnostics) { put("since", since) }.diagnosticsSnapshot()

    override suspend fun prepareRename(at: DocumentPosition): RenameTarget =
        client.call(Method.LspPrepareRename) { putPosition(at) }.renameTarget()

    override suspend fun rename(at: DocumentPosition, newName: String): EditResult {
        val result =
            client.call(Method.LspRename) {
                putPosition(at)
                put("newName", newName)
            }
        return result.editResult()
    }

    override suspend fun format(path: String, version: Int, range: TextRange?, options: FormatOptions): EditResult {
        val result =
            client.call(Method.LspFormat) {
                put(PATH, path)
                put(VERSION, version)
                range?.let { put("range", it.toJson()) }
                put("options", options.toJson())
            }
        return result.editResult()
    }

    override suspend fun codeActions(path: String, version: Int, range: TextRange): CodeActionsResult {
        val result =
            client.call(Method.LspCodeActions) {
                put(PATH, path)
                put(VERSION, version)
                put("range", range.toJson())
            }
        return result.codeActionsResult()
    }

    override suspend fun applyCodeAction(token: String, index: Int): EditResult {
        val result =
            client.call(Method.LspApplyCodeAction) {
                put("token", token)
                put("index", index)
            }
        return result.editResult()
    }
}
