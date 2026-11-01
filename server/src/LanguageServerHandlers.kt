package org.example.kotlineditor.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.languageServerChoiceJson
import org.example.kotlineditor.rpc.long
import org.example.kotlineditor.rpc.read
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.rpc.strings
import org.example.kotlineditor.rpc.toJson

private const val ID = "id"
private const val LANGUAGE = "language"
private const val PROJECT = "project"

/** The `lsp/` methods: the language servers of [LocalServices.languageServers]. */
internal fun languageServerHandlers(services: LocalServices): Map<Method, suspend Request.() -> JsonElement> =
    mapOf(
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
    )
