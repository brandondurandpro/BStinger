package org.example.kotlineditor.detekt

import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DenseExpressionTest {
    private val rule = DenseExpression(Config.empty)

    private fun findings(code: String) = rule.lint(code, compile = false)

    @Test
    fun `a chain of casts and elvis operators is reported`() {
        val code =
            """
            fun syncKindOf(sync: Any?): Int? {
                val kind = (sync as? Int) ?: ((sync as? Map<*, *>)?.get("change") as? Int)
                return kind
            }
            """.trimIndent()
        val found = findings(code)
        assertEquals(1, found.size)
        assertTrue("4 ?:, as?, && or ||" in found.single().message, found.single().message)
    }

    @Test
    fun `the same logic in named steps is fine`() {
        val code =
            """
            fun syncKindOf(sync: Any?): Int? {
                val syncAsInt = sync as? Int
                val syncAsMap = sync as? Map<*, *>
                val changeElement = syncAsMap?.get("change") as? Int
                return syncAsInt ?: changeElement
            }
            """.trimIndent()
        assertEquals(emptyList(), findings(code).map { it.message })
    }

    @Test
    fun `conditions are counted with the expression that holds them`() {
        val code =
            """
            fun ok(a: Boolean, b: Boolean, c: Boolean) = a && b || c
            fun tooMany(a: Boolean, b: Boolean, c: Boolean, d: Boolean) = a && b || c && d
            fun inIf(a: Boolean, b: Boolean, c: Boolean, d: Boolean) {
                if (a && b || c && d) println()
            }
            """.trimIndent()
        assertEquals(2, findings(code).size)
    }

    @Test
    fun `arguments, branches and lambdas are counted apart`() {
        val code =
            """
            fun f(a: String?, b: String?, c: String?, d: String?, list: List<String?>) {
                println(listOf(a ?: b, c ?: d, a ?: d))
                val x = if (a != null && b != null) a ?: c else d ?: b
                list.map { it ?: a ?: b }.filter { it != null && it.isNotEmpty() || a == null }
                println(x)
            }
            """.trimIndent()
        assertEquals(emptyList(), findings(code).map { it.message })
    }

    @Test
    fun `the limit can be configured`() {
        val code = "fun f(a: Boolean, b: Boolean, c: Boolean, d: Boolean) = a && b || c && d"
        val lenient = DenseExpression(TestConfig("maxOperators" to 3))
        assertEquals(0, lenient.lint(code, compile = false).size)
    }
}
