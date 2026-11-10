package org.example.kotlineditor.lsp.intelligence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HoverOverloadsTest {
    private val hover = lines("```typescript", "var Date: DateConstructor", "new () => Date (+3 overloads)", "```")

    private fun lines(vararg lines: String): String = lines.joinToString("\n")

    @Test
    fun `only a hover that counts overloads is completed`() {
        assertTrue(HoverOverloads.hidesSome(hover))
        assertFalse(HoverOverloads.hidesSome(lines("```typescript", "const day: Date", "```")))
        assertFalse(HoverOverloads.hidesSome(null))
    }

    @Test
    fun `the listing replaces the count, constructors keep new`() {
        val listed = HoverOverloads.listing(hover, listOf("Date(): Date", "Date(value: number): Date"))
        assertFalse(listed.contains("overloads"))
        val block = lines("```typescript", "new Date(): Date", "new Date(value: number): Date", "```")
        assertTrue(listed.endsWith(block), listed)
    }

    @Test
    fun `a function keeps its names as they are, and no signature leaves just the count removed`() {
        val function = lines("```typescript", "function f(a: string): void (+1 overload)", "```")
        val listed = HoverOverloads.listing(function, listOf("f(a: string): void", "f(a: number): void"))
        assertTrue(listed.endsWith(lines("```typescript", "f(a: string): void", "f(a: number): void", "```")), listed)
        val bare = lines("```typescript", "function f(a: string): void", "```")
        assertEquals(bare, HoverOverloads.listing(function, emptyList()))
    }
}
