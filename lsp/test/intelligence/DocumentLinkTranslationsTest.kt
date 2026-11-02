package org.example.kotlineditor.lsp.intelligence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.example.kotlineditor.model.TextRange
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val RANGE = """"range":{"start":{"line":1,"character":2},"end":{"line":1,"character":9}}"""

/** The targets of documentLink answers: files (at a line), web addresses, none. */
class DocumentLinkTranslationsTest {
    private val file = Paths.get("/p/Greeter.php").toUri().toString()

    private fun link(fields: String) = documentLinkOf(Json.parseToJsonElement("{$RANGE$fields}").jsonObject)

    @Test
    fun `a file target opens the file, at the line of its fragment`() {
        val top = link(""","target":"$file"""")
        assertEquals(Paths.get("/p/Greeter.php").toString(), top?.path)
        assertNull(top?.line)
        assertEquals(TextRange(1, 2, 1, 9), top?.range)
        assertEquals(11, link(""","target":"$file#L12"""")?.line)
        assertEquals(4, link(""","target":"$file#5,3"""")?.line)
        assertNull(link(""","target":"$file#section"""")?.line)
    }

    @Test
    fun `any other target goes to the browser, and a link without one is left out`() {
        val web = link(""","target":"https://example.com/a#b","tooltip":"Docs"""")
        assertEquals("https://example.com/a#b", web?.url)
        assertEquals("Docs", web?.tooltip)
        assertNull(web?.path)
        assertEquals("mailto:a@b.c", link(""","target":"mailto:a@b.c"""")?.url)
        assertNull(link(""))
    }
}
