package org.example.kotlineditor.lsp.supervisor

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// What the IDE tells a language server it can do, in `initialize`.

private const val LINK_SUPPORT = "linkSupport"
private const val DYNAMIC_REGISTRATION = "dynamicRegistration"
private const val VALUE_SET = "valueSet"
private const val TAG_UNNECESSARY = 1
private const val TAG_DEPRECATED = 2
private val TEXT_FORMATS = listOf("markdown", "plaintext")
private val CODE_ACTION_KINDS =
    listOf(
        "",
        "quickfix",
        "refactor",
        "refactor.extract",
        "refactor.inline",
        "refactor.rewrite",
        "source",
        "source.organizeImports",
    )

/** Workspace edits (with file creations), server commands and their `workspace/applyEdit`. */
internal val WORKSPACE_CAPABILITIES: JsonObject =
    buildJsonObject {
        put("configuration", true)
        put("workspaceFolders", true)
        put("applyEdit", true)
        putJsonObject("workspaceEdit") {
            put("documentChanges", true)
            putJsonArray("resourceOperations") { add("create") }
        }
        putJsonObject("executeCommand") { put(DYNAMIC_REGISTRATION, false) }
        putJsonObject("symbol") { put(DYNAMIC_REGISTRATION, false) }
        putJsonObject("didChangeWatchedFiles") { put(DYNAMIC_REGISTRATION, false) }
    }

/** The requests about a document the IDE makes, and the forms of answers it reads. */
internal val TEXT_DOCUMENT_CAPABILITIES: JsonObject =
    buildJsonObject {
        putJsonObject("synchronization") {
            put(DYNAMIC_REGISTRATION, false)
            put("didSave", true)
        }
        putJsonObject("publishDiagnostics") {
            put("relatedInformation", false)
            putJsonObject("tagSupport") {
                putJsonArray(VALUE_SET) {
                    add(TAG_UNNECESSARY)
                    add(TAG_DEPRECATED)
                }
            }
            put("versionSupport", true)
        }
        putJsonObject("hover") { putJsonArray("contentFormat") { TEXT_FORMATS.forEach { add(it) } } }
        putJsonObject("definition") { put(LINK_SUPPORT, true) }
        putJsonObject("typeDefinition") { put(LINK_SUPPORT, true) }
        putJsonObject("implementation") { put(LINK_SUPPORT, true) }
        putJsonObject("references") {}
        putJsonObject("documentHighlight") {}
        putJsonObject("documentSymbol") { put("hierarchicalDocumentSymbolSupport", true) }
        putJsonObject("selectionRange") {}
        putJsonObject("foldingRange") { put("lineFoldingOnly", true) }
        putJsonObject("inlayHint") {}
        putJsonObject("callHierarchy") {}
        putJsonObject("typeHierarchy") {}
        put("signatureHelp", SIGNATURE_HELP)
        putJsonObject("rename") { put("prepareSupport", true) }
        putJsonObject("formatting") {}
        putJsonObject("rangeFormatting") {}
        put("codeAction", CODE_ACTION)
        putJsonObject("completion") {
            put("contextSupport", false)
            putJsonObject("completionItem") {
                // The editor inserts plain text: no tab stops to fill.
                put("snippetSupport", false)
                put("insertReplaceSupport", false)
                putJsonArray("documentationFormat") { TEXT_FORMATS.forEach { add(it) } }
                putJsonObject("resolveSupport") { putJsonArray("properties") { add("documentation") } }
            }
        }
    }

private val SIGNATURE_HELP: JsonObject
    get() =
        buildJsonObject {
            putJsonObject("signatureInformation") {
                putJsonArray("documentationFormat") { TEXT_FORMATS.forEach { add(it) } }
                putJsonObject("parameterInformation") { put("labelOffsetSupport", true) }
                put("activeParameterSupport", true)
            }
        }

private val CODE_ACTION: JsonObject
    get() =
        buildJsonObject {
            put("isPreferredSupport", true)
            putJsonObject("disabledSupport") {}
            putJsonObject("codeActionLiteralSupport") {
                putJsonObject("codeActionKind") { putJsonArray(VALUE_SET) { CODE_ACTION_KINDS.forEach { add(it) } } }
            }
        }
