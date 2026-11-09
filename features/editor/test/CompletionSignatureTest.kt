package org.example.kotlineditor.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompletionSignatureTest {
    @Test
    fun `a method shows its return type`() {
        assertEquals("number", finalTypeOf("(method) Date.getDate(): number"))
        assertEquals("void", finalTypeOf("(function) setDate(oDate: Date, days: number): void"))
    }

    @Test
    fun `a parameter, a property and a variable show their type`() {
        assertEquals("Date", finalTypeOf("(parameter) oDate: Date"))
        assertEquals("string | undefined", finalTypeOf("(property) Foo.bar?: string | undefined"))
        assertEquals("Map<string, number>", finalTypeOf("const cache: Map<string, number>"))
    }

    @Test
    fun `a function type keeps its arrow`() {
        assertEquals("(a: string) => void", finalTypeOf("(property) Foo.run: (a: string) => void"))
    }

    @Test
    fun `a signature without a type is left as it is`() {
        assertEquals("class Date", finalTypeOf("class Date"))
    }

    @Test
    fun `the signature of a resolved item is its first fenced block`() {
        val markdown = listOf("```", "(method) a(): b", "```", "", "Docs").joinToString("\n")
        assertEquals("(method) a(): b", signatureOf(markdown))
        assertNull(signatureOf("Just docs"))
    }
}
