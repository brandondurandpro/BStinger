package org.example.kotlineditor.lsp.intelligence

import kotlinx.serialization.json.Json
import org.example.kotlineditor.model.HierarchyDirection
import org.example.kotlineditor.model.TextRange
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class HierarchyTranslationsTest {
    private val file = Paths.get("calls.php").toAbsolutePath()
    private val uri = file.toUri().toString()

    private fun item(name: String, line: Int) =
        """
        {"name": "$name", "kind": 12, "uri": "$uri", "detail": "d",
        "range": {"start": {"line": $line, "character": 0}, "end": {"line": ${line + 3}, "character": 1}},
        "selectionRange": {"start": {"line": $line, "character": 9}, "end": {"line": $line, "character": 13}}}
        """.trimIndent()

    @Test
    fun `items go to their name, outside of file URIs they are left out`() {
        val phar = """{"name": "x", "kind": 12, "uri": "phar:///a.phar/x.php", "range": {}, "selectionRange": {}}"""
        val items = hierarchyItemsOf(Json.parseToJsonElement("[${item("leaf", 1)}, $phar]"))
        assertEquals(listOf("leaf"), items.map { it.name })
        assertEquals(TextRange(1, 9, 1, 13), items.single().location.range)
        assertEquals(file.toString(), items.single().location.path)
        assertEquals("d", items.single().detail)
    }

    @Test
    fun `a caller goes to its first call and counts them, a callee goes to its declaration`() {
        val calls =
            """
            [{"${"$"}{side}": ${item("middle", 5)}, "fromRanges": [
            {"start": {"line": 7, "character": 11}, "end": {"line": 7, "character": 15}},
            {"start": {"line": 7, "character": 20}, "end": {"line": 7, "character": 24}}]}]
            """.trimIndent()
        val callers = callsOf(Json.parseToJsonElement(calls.replace("\${side}", "from")), HierarchyDirection.Callers)
        assertEquals(TextRange(7, 11, 7, 15), callers.single().location.range)
        assertEquals(2, callers.single().calls)
        val callees = callsOf(Json.parseToJsonElement(calls.replace("\${side}", "to")), HierarchyDirection.Callees)
        assertEquals(TextRange(5, 9, 5, 13), callees.single().location.range)
        assertEquals(emptyList(), callsOf(Json.parseToJsonElement("null"), HierarchyDirection.Callers))
    }
}
