package org.example.kotlineditor.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import java.net.URI

private val LOGICAL_OPERATORS = setOf(KtTokens.ELVIS, KtTokens.ANDAND, KtTokens.OROR)

/**
 * An expression that chains too many `?:`, `as?`, `&&` and `||`: each step should be a named local variable (see
 * AGENTS.md, "Mandatory Code Decomposition").
 *
 * An expression is counted from where it starts (a statement, a declaration's initializer or body, an argument, a
 * condition) down to its leaves; a lambda, a block, an argument or a condition inside it is counted apart.
 *
 * ```
 * // Reported: three operators.
 * val kind = (sync as? JsonPrimitive)?.intOrNull ?: (sync as? JsonObject)?.get("change")
 * // Fine: one step each.
 * val syncAsPrimitive = sync as? JsonPrimitive
 * val syncAsObject = sync as? JsonObject
 * val kind = syncAsPrimitive?.intOrNull ?: syncAsObject?.get("change")
 * ```
 */
class DenseExpression(config: Config) :
    Rule(
        config,
        "Too many ?:, as?, && and || in one expression: name the intermediate steps.",
        URI("https://github.com/brandondurandpro/BStinger/blob/main/AGENTS.md"),
    ) {
    private val maxOperators: Int by config(defaultValue = 2)

    override fun visitExpression(expression: KtExpression) {
        super.visitExpression(expression)
        if (!isRoot(expression)) return
        val operators = operatorsIn(expression)
        if (operators > maxOperators) {
            val message =
                "This expression has $operators ?:, as?, && or || (at most $maxOperators): split it into " +
                    "named local variables."
            report(Finding(Entity.from(expression), message))
        }
    }

    // Where an expression starts: its parent is not an expression, or is a block or a declaration.
    private fun isRoot(expression: KtExpression): Boolean {
        if (expression is KtBlockExpression || expression is KtDeclaration) return false
        val parent = expression.parent
        val parentIsExpression = parent is KtExpression
        val parentStartsScope = parent is KtBlockExpression || parent is KtDeclaration
        return !parentIsExpression || parentStartsScope
    }

    private fun operatorsIn(root: KtExpression): Int {
        var count = 0
        root.accept(
            object : KtTreeVisitorVoid() {
                override fun visitElement(element: com.intellij.psi.PsiElement) {
                    // A lambda, a block, an argument or a condition inside is counted on its own.
                    val isNested = element !== root
                    val isScope = element is KtLambdaExpression || element is KtBlockExpression
                    val isOtherRoot = element is KtExpression && isRoot(element)
                    val countedApart = isScope || isOtherRoot
                    if (isNested && countedApart) return
                    if (isCountedOperator(element)) count++
                    super.visitElement(element)
                }
            },
        )
        return count
    }

    private fun isCountedOperator(element: com.intellij.psi.PsiElement): Boolean {
        val binary = element as? KtBinaryExpression
        val binaryToken = binary?.operationToken
        val isLogical = binaryToken in LOGICAL_OPERATORS
        val typeCast = element as? KtBinaryExpressionWithTypeRHS
        val castToken = typeCast?.operationReference?.getReferencedNameElementType()
        val isSafeCast = castToken == KtTokens.AS_SAFE
        return isLogical || isSafeCast
    }
}
