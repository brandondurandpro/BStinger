package org.example.kotlineditor.syntax.treesitter

import io.github.treesitter.jtreesitter.Node

/** Where a Dockerfile holds shell code: the commands of `RUN`, `CMD`... in shell form, and the heredocs of `RUN`. */
internal object DockerfileInjections {
    private const val BASH = "bash"
    private const val SHELL_COMMAND = "shell_command"
    private const val SHELL_FRAGMENT = "shell_fragment"
    private const val CONTINUATION = "line_continuation"
    private const val COMMENT = "comment"
    private const val HEREDOC_MARKER = "heredoc_marker"
    private const val HEREDOC_BLOCK = "heredoc_block"
    private const val HEREDOC_LINE = "heredoc_line"
    private const val RUN = "run_instruction"

    fun find(root: Node): List<Injection> {
        val found = ArrayList<Injection>()
        collect(root, found)
        return found
    }

    private fun collect(node: Node, found: MutableList<Injection>) {
        when (node.type) {
            SHELL_COMMAND -> addCommand(node, found)
            RUN -> addHeredocs(node, found)
        }
        for (child in node.children) collect(child, found)
    }

    // The fragments of a command, and what sits between them (a `\` and a comment line), so that the shell sees the
    // command as the Dockerfile joins it. A heredoc marker (`RUN <<EOF`) is left out: its body is the script.
    private fun addCommand(command: Node, found: MutableList<Injection>) {
        for (child in command.children) {
            val isCode = child.type == SHELL_FRAGMENT || child.type == CONTINUATION || child.type == COMMENT
            val isHeredoc = child.children.any { it.type == HEREDOC_MARKER }
            if (isCode && !isHeredoc) found += part(child)
        }
    }

    private fun addHeredocs(run: Node, found: MutableList<Injection>) {
        val blocks = run.children.filter { it.type == HEREDOC_BLOCK }
        for (block in blocks) {
            for (line in block.children.filter { it.type == HEREDOC_LINE }) found += part(line)
        }
    }

    private fun part(node: Node) = Injection(BASH, node.startByte / 2, node.endByte / 2)
}
