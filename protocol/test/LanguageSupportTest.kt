package org.example.kotlineditor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The checklist of a language declared in [LanguageSupport]: what a new one must not forget. */
class LanguageSupportTest {
    @Test
    fun `every language is listed in all`() {
        val declared = LanguageSupport::class.java.permittedSubclasses.map { it.simpleName }.toSet()
        val listed = LanguageSupport.all.map { it::class.java.simpleName }.toSet()
        assertEquals(declared, listed, "a language declared but missing from LanguageSupport.all")
    }

    @Test
    fun `an extension belongs to one language`() {
        val extensions = LanguageSupport.all.flatMap { it.extensions }
        assertEquals(extensions.size, extensions.toSet().size, "extensions: $extensions")
        assertTrue(extensions.all { it == it.lowercase() && !it.startsWith(".") && it.isNotEmpty() })
    }

    @Test
    fun `every language has a name, extensions and an icon that shows`() {
        for (language in LanguageSupport.all) {
            val label = language.name
            assertTrue(language.name.isNotBlank(), "$label has no name")
            assertTrue(language.extensions.isNotEmpty(), "$label has no extension")
            val hasLabel = language.icon.label.isNotEmpty()
            val needsLabel = language.icon.shape != IconShape.Vue
            assertEquals(needsLabel, hasLabel, "the icon of $label: a label unless it is the Vue logo")
        }
    }

    @Test
    fun `every language server of a language and its companions is known by name`() {
        val served = LanguageSupport.all.mapNotNull { it.lsp }
        for (lsp in served) {
            val languages = listOf(lsp.group) + lsp.companions
            for (language in languages) {
                assertTrue(language in Languages.serverLanguages, "${lsp.identifier}: $language has no name in Languages")
            }
        }
    }

    @Test
    fun `the icon of a file comes from its extension, whatever its case`() {
        assertNotNull(LanguageSupport.forFileName("App.VUE"))
        assertEquals(LanguageSupport.Vue, LanguageSupport.forFileName("src/App.vue"))
        assertEquals(LanguageSupport.TypeScriptReact, LanguageSupport.forFileName("a.tsx"))
        assertEquals(null, LanguageSupport.forFileName("Makefile"))
    }

    @Test
    fun `Languages reads what LanguageSupport declares`() {
        assertEquals("typescriptreact", Languages.identifierOf("a.tsx"))
        assertEquals(Languages.TYPESCRIPT, Languages.languageOf("a.vue"))
        assertEquals(listOf(Languages.VUE_SERVER, Languages.TAILWIND), Languages.companionsOf("a.vue"))
        assertEquals("composer.json", Languages.rootMarkerOf("php"))
        assertEquals(false, Languages.hasInsight("a.html"))
        assertEquals(null, Languages.languageOf("a.kt"))
    }
}
