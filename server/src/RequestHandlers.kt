package org.example.kotlineditor.server

import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.content
import org.example.kotlineditor.rpc.diffLines
import org.example.kotlineditor.rpc.entries
import org.example.kotlineditor.rpc.entry
import org.example.kotlineditor.rpc.entryOrNull
import org.example.kotlineditor.rpc.gitStatus
import org.example.kotlineditor.rpc.keyEvent
import org.example.kotlineditor.rpc.matches
import org.example.kotlineditor.rpc.session
import org.example.kotlineditor.rpc.strings
import org.example.kotlineditor.rpc.symbols

/** Implementation of each [Method]: decode the arguments, call the local services, encode the answer. */
internal fun requestHandlers(services: LocalServices): Map<Method, suspend Request.() -> Unit> {
    val fs = services.fileSystem
    val search = services.search
    return mapOf(
        Method.ResolveDirectory to { answer.entryOrNull(fs.resolveDirectory(args.string())) },
        Method.ListDirectory to { answer.entries(fs.list(args.entry())) },
        Method.Read to {
            answer.content(fs.read(args.entry()))
            code = true
        },
        Method.FileAt to { answer.entryOrNull(fs.fileAt(args.string())) },
        Method.Write to { fs.write(args.entry(), args.string()) },
        Method.CreateFile to { answer.entry(fs.createFile(args.entry(), args.string())) },
        Method.CreateDirectory to { answer.entry(fs.createDirectory(args.entry(), args.string())) },
        Method.Rename to { answer.entry(fs.rename(args.entry(), args.string())) },
        Method.Delete to { fs.delete(args.entry()) },
        Method.WalkFiles to { answer.entries(search.walkFiles(args.entry(), args.int())) },
        Method.FindInFiles to {
            answer.matches(search.findInFiles(args.entry(), args.string(), args.bool(), args.int()))
        },
        Method.Symbols to { answer.symbols(search.symbols(args.entry())) },
        Method.GitStatus to { answer.gitStatus(services.git.status(args.string())) },
        Method.GitBranch to { answer.string(services.git.branch(args.string())) },
        Method.RecentLoad to { answer.strings(services.recentProjects.load()) },
        Method.RecentSave to { services.recentProjects.save(args.strings()) },
        Method.SessionLoad to { answer.session(services.sessions.load(args.string())) },
        Method.SessionSave to {
            services.sessions.save(args.string(), checkNotNull(args.session()) { "Missing session" })
        },
        Method.TerminalStart to {
            terminals.start(id, args.string(), args.int(), args.int(), args.int(), args.int())
        },
        Method.TerminalWrite to { terminalOrFail().write(args.bytes()) },
        Method.TerminalKey to { terminalOrFail().sendKey(args.keyEvent()) },
        Method.TerminalPaste to { terminalOrFail().paste(args.string()) },
        Method.TerminalResize to {
            val terminal = terminalOrFail()
            terminal.resize(args.int(), args.int(), args.int(), args.int())
        },
        Method.TerminalScroll to { terminalOrFail().scroll(args.int()) },
        Method.TerminalClear to { terminalOrFail().clear() },
        Method.TerminalResume to { terminals[args.long()]?.resume(args.long()) },
        Method.TerminalDestroy to { terminals.stop(args.long()) },
        Method.GitDiff to { answer.diffLines(services.git.diff(args.string(), args.string())) },
    )
}

private fun Request.terminalOrFail(): ServerTerminal =
    checkNotNull(terminals[args.long()]) { "This terminal has ended" }
