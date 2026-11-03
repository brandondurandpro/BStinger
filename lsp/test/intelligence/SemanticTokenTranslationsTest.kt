package org.example.kotlineditor.lsp.intelligence

import kotlinx.serialization.json.Json
import org.example.kotlineditor.model.SemanticToken
import kotlin.test.Test
import kotlin.test.assertEquals

class SemanticTokenTranslationsTest {
    private val legend =
        semanticLegendOf(
            Json.parseToJsonElement(
                """{"legend": {"tokenTypes": ["class", "method", "parameter"], """ +
                    """"tokenModifiers": ["declaration", "static"]}}""",
            ),
        )

    @Test
    fun `the legend names the numbers of the server`() {
        assertEquals(listOf("class", "method", "parameter"), legend.types)
        assertEquals(listOf("declaration", "static"), legend.modifiers)
        assertEquals(SemanticLegend(emptyList(), emptyList()), semanticLegendOf(null))
    }

    @Test
    fun `relative positions add up, the column restarting on each new line`() {
        // class at 1:6 (declaration), static method at 1:20, parameter at 3:4, an unknown type left out.
        val data = """{"data": [1, 6, 5, 0, 1,  0, 14, 4, 1, 2,  2, 4, 6, 2, 0,  0, 8, 3, 9, 0]}"""
        val expected =
            listOf(
                SemanticToken(1, 6, 5, "class", setOf("declaration")),
                SemanticToken(1, 20, 4, "method", setOf("static")),
                SemanticToken(3, 4, 6, "parameter", emptySet()),
            )
        assertEquals(expected, semanticTokensOf(Json.parseToJsonElement(data), legend))
        assertEquals(emptyList(), semanticTokensOf(Json.parseToJsonElement("null"), legend))
        // A truncated answer keeps its whole tokens.
        assertEquals(
            expected.take(1),
            semanticTokensOf(Json.parseToJsonElement("""{"data": [1, 6, 5, 0, 1, 0, 14]}"""), legend),
        )
    }
}
