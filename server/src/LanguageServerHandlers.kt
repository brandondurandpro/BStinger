package org.example.kotlineditor.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.example.kotlineditor.model.NavigationKind
import org.example.kotlineditor.rpc.InvalidParamsException
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.bool
import org.example.kotlineditor.rpc.documentPosition
import org.example.kotlineditor.rpc.formatOptions
import org.example.kotlineditor.rpc.highlightsJson
import org.example.kotlineditor.rpc.textRange
import org.example.kotlineditor.rpc.int
import org.example.kotlineditor.rpc.languageServerChoiceJson
import org.example.kotlineditor.rpc.long
import org.example.kotlineditor.rpc.read
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.rpc.strings
import org.example.kotlineditor.rpc.textChanges
import org.example.kotlineditor.rpc.toJson

private const val ID = "id"
private const val LANGUAGE = "language"
private const val PROJECT = "project"
private const val PATH = "path"
private const val VERSION = "version"

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
    ) + codeIntelligenceHandlers(services)

/** The code intelligence methods: hover, navigation, usages, diagnostics, edits. */
private fun codeIntelligenceHandlers(services: LocalServices): Map<Method, Handler> {
    val intelligence = { services.languageServers.intelligence }
    return mapOf<Method, Handler>(
        Method.LspHover to { intelligence().hover(session, params.documentPosition()).toJson() },
        Method.LspNavigate to {
            val kindName = params.string("kind")
            val kind = NavigationKind.entries.firstOrNull { it.name == kindName } ?: throw InvalidParamsException("Unknown kind $kindName")
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
        Method.LspDiagnostics to { intelligence().diagnostics(session, params.long("since")).toJson() },
        Method.LspPrepareRename to { intelligence().prepareRename(session, params.documentPosition()).toJson() },
        Method.LspRename to {
            intelligence().rename(session, params.documentPosition(), params.string("newName")).toJson()
        },
        Method.LspFormat to {
            val range = params["range"]?.takeIf { it != JsonNull }?.let { params.read("range") { it.textRange() } }
            val options = params.read("options") { it.formatOptions() }
            intelligence().format(session, params.string(PATH), params.int(VERSION), range, options).toJson()
        },
        Method.LspCodeActions to {
            val range = params.read("range") { it.textRange() }
            intelligence().codeActions(session, params.string(PATH), params.int(VERSION), range).toJson()
        },
        Method.LspApplyCodeAction to {
            intelligence().applyCodeAction(session, params.string("token"), params.int("index")).toJson()
        },
    )
}
