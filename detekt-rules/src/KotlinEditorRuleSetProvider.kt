package org.example.kotlineditor.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/** The rules of `kotlin-editor` (its section of `detekt.yml`). */
class KotlinEditorRuleSetProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("kotlin-editor")

    override fun instance(): RuleSet = RuleSet(ruleSetId, listOf(::DenseExpression))
}
