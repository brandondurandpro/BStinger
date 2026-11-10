package org.example.kotlineditor.lsp

import org.example.kotlineditor.lsp.provision.CandidateRegistry
import org.example.kotlineditor.model.LanguageSupport
import kotlin.test.Test
import kotlin.test.assertEquals

/** The language server facet of a language declared in [LanguageSupport]: a server to start, or a stated lack. */
class LanguageSupportServersTest {
    private val candidates = CandidateRegistry()

    // Languages whose files only their companion servers know (Tailwind CSS): no server of their own.
    private val companionsOnly = setOf("html", "css")

    @Test
    fun `the language of every server-handled file has a server to start, or is served by companions only`() {
        val served = LanguageSupport.all.mapNotNull { it.lsp }
        val groups = served.map { it.group }.toSet()
        val without = groups.filter { candidates.offered(it).isEmpty() && it !in companionsOnly }
        val advice = "declare its server in the candidates, or list the language in companionsOnly"
        assertEquals(emptyList(), without, advice)
    }

    @Test
    fun `a language served by companions only has no server of its own, and its files have companions`() {
        for (group in companionsOnly) assertEquals(emptyList(), candidates.offered(group).map { it.id })
        val onlyCompanions = LanguageSupport.all.filter { it.lsp?.group in companionsOnly }
        for (language in onlyCompanions) {
            val companions = language.lsp?.companions.orEmpty()
            assertEquals(false, companions.isEmpty(), "${language.name} would have no server at all")
        }
    }

    @Test
    fun `every companion server of a language exists`() {
        val companions = LanguageSupport.all.flatMap { it.lsp?.companions.orEmpty() }.toSet()
        val without = companions.filter { candidates.offered(it).isEmpty() }
        assertEquals(emptyList(), without, "a companion language without a server")
    }
}
