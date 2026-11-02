package org.example.kotlineditor.lsp.intelligence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The trigger characters of a documentOnTypeFormattingProvider. */
class TypeFormattingTranslationsTest {
    @Test
    fun `the first trigger character comes before the others, a missing provider has none`() {
        val provider = Json.parseToJsonElement("""{"firstTriggerCharacter":";","moreTriggerCharacter":["}","\n"]}""")
        assertEquals(listOf(";", "}", "\n"), triggersOf(provider.jsonObject))
        assertEquals(listOf("}"), triggersOf(Json.parseToJsonElement("""{"moreTriggerCharacter":["}"]}""").jsonObject))
        assertEquals(emptyList(), triggersOf(null))
    }
}
