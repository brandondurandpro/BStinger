package org.example.kotlineditor.lsp

import kotlin.test.Test
import kotlin.test.assertEquals

/** Keeps the module's test task alive until real tests arrive. */
class LspTest {
    @Test
    fun `follows LSP 3_17`() = assertEquals("3.17", Lsp.SPEC_VERSION)
}
