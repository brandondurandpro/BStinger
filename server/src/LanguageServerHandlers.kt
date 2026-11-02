package org.example.kotlineditor.server

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.EditResult
import org.example.kotlineditor.model.HierarchyDirection
import org.example.kotlineditor.model.HierarchyKind
import org.example.kotlineditor.model.NavigationKind
import org.example.kotlineditor.rpc.InvalidParamsException
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.bool
import org.example.kotlineditor.rpc.documentPosition
import org.example.kotlineditor.rpc.fileOperationNamed
import org.example.kotlineditor.rpc.formatOptions
import org.example.kotlineditor.rpc.hierarchyItem
import org.example.kotlineditor.rpc.highlightsJson
import org.example.kotlineditor.rpc.int
import org.example.kotlineditor.rpc.languageServerChoiceJson
import org.example.kotlineditor.rpc.long
import org.example.kotlineditor.rpc.operatedFiles
import org.example.kotlineditor.rpc.read
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.rpc.strings
import org.example.kotlineditor.rpc.textChanges
import org.example.kotlineditor.rpc.textRange
import org.example.kotlineditor.rpc.toJson

private const val RANGE = "range"

private const val ID = "id"
private const val LANGUAGE = "language"
private const val PROJECT = "project"
private const val PATH = "path"
private const val VERSION = "version"
private const val TOKEN = "token"
private const val INDEX = "index"

/** The `lsp/` methods: the language servers of [LocalServices.languageServers]. */
internal fun languageServerHandlers(services: LocalServices): Map<Method, Handler> =
    mapOf<Method, Handler>(
        Method.LspStatus to { services.languageServers.status(params.long("since")).toJson() },
        Method.LspUse to {
            services.languageServers
                .use(session, params.string(PROJECT), params.read("files") { it.strings() })
                .toJson()
        },
        Method.LspRestart to {
            services.languageServers.restart(params.string(ID), params.long("generation"))
            JsonNull
        },
        Method.LspStop to {
            services.languageServers.stop(params.string(ID))
            JsonNull
        },
        Method.LspStart to {
            services.languageServers.start(params.string(ID))
            JsonNull
        },
        Method.LspLog to { services.languageServers.log(params.string(ID)).toJson() },
        Method.LspChoice to {
            services.languageServers.choice(params.string(LANGUAGE), params.string(PROJECT)).languageServerChoiceJson()
        },
        Method.LspChoose to {
            services.languageServers.choose(params.string(LANGUAGE), params.string(PROJECT), params.string("candidate"))
            JsonNull
        },
        Method.DocOpen to {
            val path = params.string(PATH)
            val text = params.string("text")
            services.languageServers.openDocument(session, params.string(PROJECT), path, params.int(VERSION), text)
            JsonNull
        },
        Method.DocChange to {
            val changes = params.read("changes") { it.textChanges() }
            services.languageServers.changeDocument(session, params.string(PATH), params.int(VERSION), changes)
            JsonNull
        },
        Method.DocClose to {
            services.languageServers.closeDocument(session, params.string(PATH))
            JsonNull
        },
        Method.LspCompletion to {
            services.languageServers
                .complete(session, params.string(PATH), params.int(VERSION), params.int("line"), params.int("column"))
                .toJson()
        },
        Method.LspResolveCompletion to {
            val documentation =
                services.languageServers.resolveCompletion(session, params.string(TOKEN), params.int(INDEX))
            buildJsonObject { documentation?.let { put("documentation", it) } }
        },
    ) +
        codeIntelligenceHandlers(services) +
        editingHandlers(services) +
        structureHandlers(services)

// The code intelligence methods: hover, navigation, usages, diagnostics, edits.
private fun codeIntelligenceHandlers(services: LocalServices): Map<Method, Handler> {
    val intelligence = { services.languageServers.intelligence }
    return mapOf<Method, Handler>(
        Method.LspHover to { intelligence().hover(session, params.documentPosition()).toJson() },
        Method.LspNavigate to {
            val kindName = params.string("kind")
            val kind =
                NavigationKind.entries.firstOrNull { it.name == kindName } ?: throw InvalidParamsException(
                    "Unknown kind $kindName",
                )
            intelligence().navigate(session, params.documentPosition(), kind).toJson()
        },
        Method.LspReferences to {
            intelligence().references(session, params.documentPosition(), params.bool("includeDeclaration")).toJson()
        },
        Method.LspHighlights to { intelligence().highlights(session, params.documentPosition()).highlightsJson() },
        Method.LspSignatureHelp to { intelligence().signatureHelp(session, params.documentPosition()).toJson() },
        Method.LspUsageCounts to {
            intelligence().usageCounts(session, params.string(PATH), params.int(VERSION)).toJson()
        },
        Method.LspWorkspaceSymbols to { intelligence().workspaceSymbols(session, params.string("query")).toJson() },
        Method.LspDiagnostics to { intelligence().diagnostics(session, params.long("since")).toJson() },
        Method.LspPrepareRename to { intelligence().prepareRename(session, params.documentPosition()).toJson() },
        Method.LspRename to {
            intelligence().rename(session, params.documentPosition(), params.string("newName")).toJson()
        },
    )
}

// The code intelligence methods that edit or act: formatting, quick fixes, links, code lenses.
private fun editingHandlers(services: LocalServices): Map<Method, Handler> {
    val intelligence = { services.languageServers.intelligence }
    return mapOf<Method, Handler>(
        Method.LspFormat to {
            val range = params[RANGE]?.takeIf { it != JsonNull }?.let { params.read(RANGE) { it.textRange() } }
            val options = params.read("options") { it.formatOptions() }
            intelligence().format(session, params.string(PATH), params.int(VERSION), range, options).toJson()
        },
        Method.LspTypeFormattingTriggers to {
            intelligence().typeFormattingTriggers(session, params.string(PATH), params.int(VERSION)).toJson()
        },
        Method.LspFormatOnType to {
            val options = params.read("options") { it.formatOptions() }
            val character = params.string("character")
            val edits = intelligence().formatOnType(session, params.documentPosition(), character, options)
            edits.toJson()
        },
        Method.LspCodeActions to {
            val range = params.read(RANGE) { it.textRange() }
            intelligence().codeActions(session, params.string(PATH), params.int(VERSION), range).toJson()
        },
        Method.LspApplyCodeAction to {
            intelligence().applyCodeAction(session, params.string(TOKEN), params.int(INDEX)).toJson()
        },
        Method.LspDocumentLinks to {
            intelligence().documentLinks(session, params.string(PATH), params.int(VERSION)).toJson()
        },
        Method.LspCodeLenses to {
            intelligence().codeLenses(session, params.string(PATH), params.int(VERSION)).toJson()
        },
        Method.LspRunCodeLens to {
            intelligence().runCodeLens(session, params.string(TOKEN), params.int(INDEX)).toJson()
        },
        Method.LspWillChangeFiles to {
            val name = params.string("operation")
            val operation = checkNotNull(fileOperationNamed(name)) { "Unknown file operation $name" }
            val files = params.read("files") { it.operatedFiles() }
            // No server runs before the first document with a language server opens: nothing to adapt.
            val started = services.languageServersStarted
            val edits = if (started) intelligence().willChangeFiles(session, operation, files) else EditResult.NotReady
            edits.toJson()
        },
    )
}

// What the language server knows of the structure of the code: outline, hierarchies, hints, folding, selection.
private fun structureHandlers(services: LocalServices): Map<Method, Handler> {
    val intelligence = { services.languageServers.intelligence }
    return mapOf<Method, Handler>(
        Method.LspOutline to {
            intelligence().outline(session, params.string(PATH), params.int(VERSION)).toJson()
        },
        Method.LspPrepareHierarchy to {
            val kind = params.enumValue("kind", HierarchyKind.entries)
            intelligence().prepareHierarchy(session, params.documentPosition(), kind).toJson()
        },
        Method.LspHierarchyChildren to {
            val item = params.getValue("item").hierarchyItem()
            val direction = params.enumValue("direction", HierarchyDirection.entries)
            intelligence().hierarchyChildren(params.string(PATH), item, direction).toJson()
        },
        Method.LspSemanticTokens to {
            intelligence().semanticTokens(session, params.string(PATH), params.int(VERSION)).toJson()
        },
        Method.LspInlayHints to {
            intelligence().inlayHints(session, params.string(PATH), params.int(VERSION)).toJson()
        },
        Method.LspFoldingRanges to {
            intelligence().foldingRanges(session, params.string(PATH), params.int(VERSION)).toJson()
        },
        Method.LspSelectionRanges to {
            intelligence().selectionRanges(session, params.documentPosition()).toJson()
        },
    )
}

// The entry of [entries] named by the string [key]; an unknown name is invalid params.
private fun <T : Enum<T>> JsonObject.enumValue(key: String, entries: List<T>): T {
    val name = string(key)
    return entries.firstOrNull { it.name == name } ?: throw InvalidParamsException("Unknown $key $name")
}
