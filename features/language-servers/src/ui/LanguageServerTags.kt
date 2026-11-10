package org.example.kotlineditor.ui

/**
 * Semantics test tags of the language-servers module, shared with the end-to-end tests.
 * Paths are relative to the project root.
 */
@Suppress("TooManyFunctions")
object LanguageServerTags {
    const val TOOL_BUTTON = "tool.languageServers"
    const val STATUS_SERVER_MEMORY = "status.serverMemory"
    const val STATUS_SERVER_MEMORY_TOOLTIP = "status.serverMemory.tooltip"
    const val LANGUAGE_SERVERS = "languageServers"
    const val LANGUAGE_SERVERS_HIDE = "languageServers.hide"
    const val LANGUAGE_SERVERS_EMPTY = "languageServers.empty"
    const val LANGUAGE_SERVERS_ERROR = "languageServers.error"
    const val LANGUAGE_SERVERS_IDE = "languageServers.ide"
    const val LANGUAGE_SERVERS_LOG = "languageServers.log"
    const val LSP_CHOICE = "lsp.choice"

    /** The button checking the whole project, the line telling what it found, and its list of problems. */
    const val PROJECT_CHECK = "languageServers.check"
    const val PROJECT_CHECK_SUMMARY = "languageServers.check.summary"
    const val PROJECT_CHECK_PROBLEMS = "languageServers.check.problems"

    /** Why a running server gives no inlay hints, under the choice of their mode. */
    const val INLAY_HINTS_NOTE = "languageServers.inlayHints.note"
    const val SETUP_NOTE = "languageServers.setup.note"
    const val LSP_CHOICE_NOT_NOW = "lsp.choice.notNow"
    const val LSP_CHOICE_OPEN = "lsp.choice.open"
    const val LSP_CHOICE_QUICK = "lsp.choice.quick"
    const val LSP_CHOICE_DIALOG = "lsp.choice.dialog"
    const val LSP_CHOICE_DETAILS = "lsp.choice.details"
    const val LSP_CHOICE_CANCEL = "lsp.choice.cancel"

    /** The button choosing how inlay hints show: Off, Popup or Inline. */
    fun inlayHintsMode(mode: String) = "languageServers.inlayHints:$mode"

    fun statusLanguageServer(id: String) = "status.languageServer:$id"

    fun statusLanguageServerTooltip(id: String) = "status.languageServer.tooltip:$id"

    fun languageServerRow(id: String) = "languageServers.row:$id"

    fun languageServerState(id: String) = "languageServers.state:$id"

    fun languageServerMemory(id: String) = "languageServers.memory:$id"

    fun languageServerMessage(id: String) = "languageServers.message:$id"

    fun languageServerRestart(id: String) = "languageServers.restart:$id"

    fun languageServerStop(id: String) = "languageServers.stop:$id"

    fun languageServerStart(id: String) = "languageServers.start:$id"

    fun languageServerLog(id: String) = "languageServers.log:$id"

    fun lspChoiceOption(candidate: String) = "lsp.choice.option:$candidate"

    fun lspChoiceUse(candidate: String) = "lsp.choice.use:$candidate"

    fun lspChoiceDetail(candidate: String) = "lsp.choice.detail:$candidate"
}
