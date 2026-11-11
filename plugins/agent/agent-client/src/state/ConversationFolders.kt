package org.example.kotlineditor.state

/** The conversations that work in one folder: the project itself, or one of its worktrees. */
class FolderGroup(val folder: String, val label: String, val conversations: List<Conversation>)

/** The folder [conversation] works in: its worktree or the project, known before its workspace opens too. */
fun AgentState.folderOf(conversation: Conversation): String {
    val opened = conversation.workspace?.root?.path
    if (opened != null) return opened.trimEnd('/', '\\')
    val known = conversation.sessionDirectory
    if (known != null) return known.trimEnd('/', '\\')
    return if (conversation.mode == WorkMode.Direct) project.root.path.trimEnd('/', '\\') else conversation.branch
}

/** The name of the project the conversations belong to: its folder. */
val AgentState.projectName: String get() = project.root.name

/**
 * [conversations] grouped by the folder they work in, in the order they come (so a list sorted by recency keeps
 * its order inside each group); the folder of the project itself first, named after the branch it is on.
 */
fun AgentState.groupByFolder(conversations: List<Conversation>): List<FolderGroup> {
    val rootFolder = project.root.path.trimEnd('/', '\\')
    val byFolder = LinkedHashMap<String, MutableList<Conversation>>()
    for (conversation in conversations) byFolder.getOrPut(folderOf(conversation)) { mutableListOf() }.add(conversation)
    val groups =
        byFolder.map { (folder, members) ->
            val name = if (folder == rootFolder) "Project folder" else folder.substringAfterLast('/').substringAfterLast('\\')
            val branch = members.firstNotNullOfOrNull { it.currentBranch }
            FolderGroup(folder, if (branch == null) name else "$name · $branch", members)
        }
    return groups.sortedByDescending { it.folder == rootFolder }
}
