package org.example.kotlineditor.state

import org.example.kotlineditor.model.InlayHint
import org.example.kotlineditor.model.InlayHintKind
import kotlin.test.Test
import kotlin.test.assertEquals

class InlayHintsTest {
    private val lines = listOf("<?php", "class Shop {", "}", "\$shop = new Shop();", "f(\$shop) }")

    @Test
    fun `the types of variables and the parameter names are kept, the names after a closing brace are not`() {
        val variable = InlayHint(3, 5, ": Shop", InlayHintKind.Type)
        val closing = InlayHint(2, 1, " class Shop", InlayHintKind.Type)
        val parameter = InlayHint(4, 2, "shop: ", InlayHintKind.Parameter)
        // A parameter name is not a block's: kept even after a brace.
        val afterBrace = InlayHint(4, 10, "x: ", InlayHintKind.Parameter)
        val outside = InlayHint(9, 1, ": Shop", InlayHintKind.Type)
        val shown = shownHints(listOf(variable, closing, parameter, afterBrace, outside), lines)
        assertEquals(listOf(variable, parameter, afterBrace, outside), shown)
    }
}
