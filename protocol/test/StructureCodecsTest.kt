package org.example.kotlineditor.rpc

import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.HierarchyItem
import org.example.kotlineditor.model.HierarchyResult
import org.example.kotlineditor.model.InlayHint
import org.example.kotlineditor.model.InlayHintKind
import org.example.kotlineditor.model.InlayHintsResult
import org.example.kotlineditor.model.SemanticToken
import org.example.kotlineditor.model.SemanticTokensResult
import org.example.kotlineditor.model.SourceLocation
import org.example.kotlineditor.model.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

/** The answers of semantic tokens, inlay hints and hierarchies go over the protocol and come back the same. */
class StructureCodecsTest {
    @Test
    fun `semantic tokens travel with their type and modifier names`() {
        val tokens =
            listOf(
                SemanticToken(0, 6, 5, "class", setOf("declaration")),
                SemanticToken(2, 4, 3, "method", setOf("static", "deprecated")),
                SemanticToken(2, 9, 2, "parameter"),
            )
        val result = SemanticTokensResult(FeatureStatus.Ready, tokens)
        assertEquals(result, result.toJson().semanticTokensResult())
        assertEquals(SemanticTokensResult.NotReady, SemanticTokensResult.NotReady.toJson().semanticTokensResult())
    }

    @Test
    fun `inlay hints and hierarchy items travel unchanged`() {
        val hints = InlayHintsResult(FeatureStatus.Ready, listOf(InlayHint(1, 4, "name: ", InlayHintKind.Parameter)))
        assertEquals(hints, hints.toJson().inlayHintsResult())
        val item =
            HierarchyItem(
                "greet",
                null,
                SourceLocation("/p/Greeter.php", TextRange(3, 20, 3, 25), "    public function greet()"),
                """{"name":"greet","uri":"file:///p/Greeter.php"}""",
                calls = 2,
            )
        val result = HierarchyResult(FeatureStatus.Ready, listOf(item, item.copy(detail = "Greeter")))
        assertEquals(result, result.toJson().hierarchyResult())
    }
}
