package org.example.kotlineditor.ui

/** Semantics test tags of agent mode (its switch, conversations, prompt, settings), shared with the end-to-end tests. */
object AgentTags {
    const val SETTINGS_AGENTS = "settings.agents"
    const val MODE_SWITCH = "mode.switch"
    const val MODE_IDE = "mode.ide"
    const val MODE_AGENT = "mode.agent"
    const val AGENT = "agent"
    const val AGENT_LIST = "agent.list"
    const val AGENT_NEW = "agent.new"
    const val AGENT_RENAME = "agent.rename"
    const val AGENT_RENAME_FIELD = "agent.rename.field"
    const val AGENT_FILTER = "agent.filter"
    const val AGENT_DELETE = "agent.delete"
    const val AGENT_TABS = "agent.tabs"
    const val AGENT_EMPTY = "agent.empty"
    const val AGENT_PROMPT = "agent.prompt"
    const val AGENT_SEND = "agent.send"
    const val AGENT_STOP = "agent.stop"
    const val AGENT_THINKING = "agent.thinking"
    const val AGENT_NAME = "agent.name"
    const val AGENT_AGENT = "agent.agent"
    const val AGENT_AGENT_DEFAULT = "agent.agent.default"
    const val AGENT_AGENT_FILTER = "agent.agent.filter"
    const val AGENT_AGENT_MAKE_DEFAULT = "agent.agent.makeDefault"
    const val AGENT_REGISTRY = "agent.registry"
    const val AGENT_MODEL = "agent.model"
    const val AGENT_PERMISSIONS = "agent.permissions"
    const val AGENT_EFFORT = "agent.effort"
    const val AGENT_COMPLETION = "agent.completion"
    const val AGENT_TERMINAL = "agent.terminal"
    const val AGENT_STRIP_CONVERSATIONS = "agent.strip.conversations"
    const val AGENT_DETAILS = "agent.details"
    const val AGENT_DETAIL_CONVERSATION = "agent.detail.conversation"
    const val AGENT_DETAIL_EDITOR = "agent.detail.editor"
    const val AGENT_WORKSPACE_STATUS = "agent.workspaceStatus"
    const val AGENT_MODE_DIRECT = "agent.mode.direct"
    const val AGENT_MODE_WORKTREE = "agent.mode.worktree"

    fun settingsAgent(id: String) = "settings.agent:$id"

    fun agentConversation(id: Int) = "agent.conversation.$id"

    fun agentTab(id: Int) = "agent.tab.$id"

    fun agentTabClose(id: Int) = "agent.tab.$id.close"

    fun agentTabDetails(id: Int) = "agent.tab.$id.details"

    fun agentView(id: Int) = "agent.view.$id"

    fun agentMessage(index: Int) = "agent.message.$index"

    /** An entry of the menu of the agent of the conversation, by its registry id (`fake-agent`). */
    fun agentAgent(id: String) = "agent.agent:$id"

    /** A model of the menu of the model of the session, by its value (`fake-smart`). */
    fun agentModel(value: String) = "agent.model:$value"

    /** A mode of the menu of the mode of permissions of the session, by its value (`accept-edits`). */
    fun agentPermissionMode(value: String) = "agent.permissions:$value"

    /** An effort of the menu of the effort of the session, by its value (`high`). */
    fun agentEffort(value: String) = "agent.effort:$value"

    /** A suggestion of the completion of the prompt, by its label (`/echo`, `src/Main.kt`). */
    fun agentCompletion(label: String) = "agent.completion:$label"

    /** Something joined to the prompt, by its label (`Main.kt`, `Main.kt:3-7`, `image-1.png`). */
    fun agentAttachment(label: String) = "agent.attachment:$label"

    /** A message of the transcript these actions copy, by its index. */
    fun agentCopy(index: Int) = "agent.copy:$index"

    /** A prompt of the transcript that goes back to the field, by its index. */
    fun agentResend(index: Int) = "agent.resend:$index"

    /** The copy of the whole transcript of the conversation. */
    const val AGENT_TRANSCRIPT = "agent.transcript"

    /** The button that removes something joined to the prompt, by its label. */
    fun agentAttachmentRemove(label: String) = "agent.attachment.remove:$label"

    /** Something joined to a prompt sent, as the transcript shows it, by its label. */
    fun agentSentAttachment(label: String) = "agent.sent.attachment:$label"

    /** A button of the pending permission request, by the id of its option (`allow`). */
    fun agentPermission(option: String) = "agent.permission:$option"
}
