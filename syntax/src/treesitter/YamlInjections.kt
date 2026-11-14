package org.example.kotlineditor.syntax.treesitter

import io.github.treesitter.jtreesitter.Node

/**
 * Where a YAML file holds shell code: the scripts of CI files (`run:` of GitHub Actions, `script:` of GitLab CI) and
 * the commands of a Compose file (`command:`, `entrypoint:`).
 */
internal object YamlInjections {
    private const val BASH = "bash"
    private const val PAIR = "block_mapping_pair"
    private const val SEQUENCE_ITEM = "block_sequence_item"
    private const val BLOCK_SCALAR = "block_scalar"
    private const val PLAIN_SCALAR = "plain_scalar"
    private const val FLOW_NODE = "flow_node"
    private const val BLOCK_NODE = "block_node"
    private const val BLOCK_SEQUENCE = "block_sequence"

    // Keys whose value is a shell script: a block (`run: |`), a line, or a list of lines for the keys of `listKeys`.
    private val scriptKeys = setOf("run", "script", "before_script", "after_script", "command", "entrypoint")
    private val listKeys = setOf("script", "before_script", "after_script")

    fun find(root: Node, textOf: (Int, Int) -> String): List<Injection> {
        val found = ArrayList<Injection>()
        collect(root, textOf, found)
        return found
    }

    private fun collect(node: Node, textOf: (Int, Int) -> String, found: MutableList<Injection>) {
        if (node.type == PAIR) addPair(node, textOf, found)
        for (child in node.children) collect(child, textOf, found)
    }

    private fun addPair(pair: Node, textOf: (Int, Int) -> String, found: MutableList<Injection>) {
        val key = pair.getChildByFieldName("key").orElse(null)
        val value = pair.getChildByFieldName("value").orElse(null)
        val name = key?.let { textOf(it.startByte / 2, it.endByte / 2).trim() }
        if (value == null || name !in scriptKeys) return
        // A block value (`key: |`, a list) holds its content in a child, after its anchor or tag.
        val content = blockContent(value)
        when (content?.type) {
            BLOCK_SCALAR -> addBlock(content, textOf, found)
            FLOW_NODE -> addPlain(content, found)
            BLOCK_SEQUENCE -> if (name in listKeys) addItems(content, found)
        }
    }

    private fun blockContent(value: Node): Node? {
        if (value.type != BLOCK_NODE) return value
        return value.children.firstOrNull { it.type != "anchor" && it.type != "tag" }
    }

    // The lines under the `|` or `>` header of a block scalar.
    private fun addBlock(block: Node, textOf: (Int, Int) -> String, found: MutableList<Injection>) {
        val start = block.startByte / 2
        val end = block.endByte / 2
        val text = textOf(start, end)
        val header = text.indexOf('\n')
        if (header >= 0) found += Injection(BASH, start + header + 1, end)
    }

    private fun addPlain(flow: Node, found: MutableList<Injection>) {
        val plain = flow.children.firstOrNull { it.type == PLAIN_SCALAR } ?: return
        found += Injection(BASH, plain.startByte / 2, plain.endByte / 2)
    }

    private fun addItems(sequence: Node, found: MutableList<Injection>) {
        for (item in sequence.children.filter { it.type == SEQUENCE_ITEM }) {
            val flow = item.children.firstOrNull { it.type == FLOW_NODE } ?: continue
            addPlain(flow, found)
        }
    }
}
