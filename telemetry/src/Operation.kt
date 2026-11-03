package org.example.kotlineditor.telemetry

/**
 * What can be measured: a fixed catalog, so that the number of series of the duration histogram is bounded (an
 * operation, an outcome and a side each). [id] names the span and the `operation` label.
 */
enum class Operation(val id: String) {
    /** From the start of `main` to the first frame of the window. */
    AppStartup("app.startup"),

    /** From the choice of a project to its tree shown. */
    ProjectOpen("project.open"),

    /** From the click on a file to its first frame in the editor. */
    EditorOpenFile("editor.open_file"),

    /** Ctrl+S: until the file is on disk. */
    EditorSave("editor.save"),

    /** Completion asked (typed or Ctrl+Space) until its list is shown. */
    EditorCompletion("editor.completion"),

    /** Find in the file: the query typed until its matches are shown. */
    EditorFind("editor.find"),

    /** A diff asked until it is shown. */
    EditorDiff("editor.diff"),

    /** The blame of a file asked until its annotations are shown. */
    EditorBlame("editor.blame"),

    /** Find Usages, Ctrl+B and the other lists of places: asked until shown. */
    EditorNavigation("editor.navigation"),

    /** A folder of the tree expanded until its children are shown. */
    TreeExpand("tree.expand"),

    /** Go to File: a query typed until its results are shown. */
    TreeGotoFile("tree.goto_file"),

    /** The listing of every file of the project (WalkFiles) for Go to File. */
    TreeIndex("tree.index"),

    /** A file or folder created, renamed or deleted from the tree. */
    TreeFileOperation("tree.file_operation"),

    /** Find in Files: from the query to the end of its results. */
    SearchFindInFiles("search.find_in_files"),

    /** The git status of the project refreshed. */
    GitStatus("git.status"),

    /** The current branch read, or another one checked out. */
    GitBranch("git.branch"),

    /** A page of the history of the Branches window. */
    GitHistory("git.history"),

    /** The diff of a commit asked until it is shown. */
    GitCommitDiff("git.commit_diff"),

    /** A shell started until its first output. */
    TerminalStart("terminal.start"),

    /** A key typed in a shell until its echo is shown (sampled). */
    TerminalEcho("terminal.echo"),

    /** A language server started until it answered `initialize`. */
    LspStart("lsp.start"),

    /** A request to a language server, on the server. */
    LspRequest("lsp.request"),

    /** The SSH connection to a remote machine. */
    RemoteConnect("remote.ssh_connect"),

    /** The IDE server deployed on a remote machine. */
    RemoteDeploy("remote.deploy"),

    /** The link with the server lost until it is back. */
    Reconnect("connection.reconnect"),

    /** A call of the protocol, on the client: from the request to its answer, through the network. */
    RpcClient("rpc.client"),

    /** A call of the protocol, on the server: its handler. */
    RpcServer("rpc.server"),
}
