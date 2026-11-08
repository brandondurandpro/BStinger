package org.example.kotlineditor.state

import kotlin.test.Test
import kotlin.test.assertEquals

class PromptTemplatesTest {
    @Test
    fun `templates survive their encoding with lines, tabs and backslashes`() {
        val templates =
            listOf(
                PromptTemplate("Review", "Review this:\n\tthe diff \\ and more"),
                PromptTemplate("Tests", "Write the tests"),
            )
        assertEquals(templates, decodeTemplates(encodeTemplates(templates)))
    }

    @Test
    fun `a damaged line is ignored`() {
        assertEquals(listOf(PromptTemplate("A", "b")), decodeTemplates("A\tb\nnonsense\n\t\n"))
    }
}
