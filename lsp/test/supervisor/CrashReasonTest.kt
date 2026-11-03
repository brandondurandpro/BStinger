package org.example.kotlineditor.lsp.supervisor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrashReasonTest {
    @Test
    fun `a Symfony error block wins over the usage printed after it, its wrapped lines joined`() {
        val stderr =
            listOf(
                "Starting language server, use -vvv for verbose output",
                "In ArgumentAssert.php line 52:",
                "                                                  ",
                """  Argument "disabledSupport" has type "bool"  """,
                """  but was passed "array"                        """,
                "                                                  ",
                "language-server [--address ADDRESS] [--no-loop]",
            )
        val reason = CrashReason.explain(stderr)
        assertEquals("""Argument "disabledSupport" has type "bool" but was passed "array"""", reason)
    }

    @Test
    fun `without a block, the last line naming an error wins`() {
        val stderr = listOf("PHP Fatal error:  Uncaught Error: oops in /a.php:3", "Stack trace:", "#0 {main}")
        assertEquals("PHP Fatal error:  Uncaught Error: oops in /a.php:3", CrashReason.explain(stderr))
    }

    @Test
    fun `otherwise the last line that is not blank`() {
        val stderr = listOf("Phpactor requires the following PHP extension(s) which are not loaded: mbstring", "  ")
        assertEquals(
            "Phpactor requires the following PHP extension(s) which are not loaded: mbstring",
            CrashReason.explain(stderr),
        )
    }

    @Test
    fun `nothing written, nothing to explain`() {
        assertNull(CrashReason.explain(emptyList()))
        assertNull(CrashReason.explain(listOf("   ")))
    }
}
