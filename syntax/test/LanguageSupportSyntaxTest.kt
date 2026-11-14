package org.example.kotlineditor.syntax

import org.example.kotlineditor.model.LanguageSupport
import org.example.kotlineditor.syntax.treesitter.GrammarSpec
import kotlin.test.Test
import kotlin.test.assertEquals

/** The syntax facet of a language declared in [LanguageSupport]: its tree-sitter grammar, or a stated lack of one. */
class LanguageSupportSyntaxTest {
    // Languages colored by the line lexer only, for want of a grammar in the catalog: a language that gets one leaves.
    private val lexerOnly = setOf(LanguageSupport.Gradle, LanguageSupport.Batch)

    @Test
    fun `every language has a tree-sitter grammar for each of its extensions, or is stated to have none`() {
        val missing = mutableListOf<String>()
        for (language in LanguageSupport.all - lexerOnly) {
            for (extension in language.extensions) {
                if (GrammarSpec.forFileName("file.$extension") == null) missing += "${language.name}: .$extension"
            }
        }
        assertEquals(emptyList(), missing, "add the grammar to the catalog, or list the language in lexerOnly")
    }

    @Test
    fun `a language stated to have no grammar has none`() {
        val grammars =
            lexerOnly.flatMap { language ->
                language.extensions.mapNotNull { GrammarSpec.forFileName("file.$it") }
            }
        assertEquals(emptyList(), grammars, "these have a grammar now: remove them from lexerOnly")
    }
}
