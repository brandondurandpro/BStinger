package org.example.kotlineditor.unit

import org.example.kotlineditor.remote.SshTarget
import org.example.kotlineditor.state.HostTagsCodec
import org.example.kotlineditor.state.parseTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostTagsCodecTest {
    @Test
    fun `tags survive a preferences round trip`() {
        val tags = mapOf("prod" to listOf("production", "dev"), "example.com:22:me" to listOf("test"))

        assertEquals(tags, HostTagsCodec.decode(HostTagsCodec.encode(tags)))
    }

    @Test
    fun `invalid lines are dropped and blank tags remove the key`() {
        val text =
            listOf(
                HostTagsCodec.encode(mapOf("prod" to listOf("production"))),
                "no tab here",
                ":no-key\tdev",
                "prod\t   ",
                "prod\tQA, qa, qa , staging",
            ).joinToString("\n")

        assertEquals(mapOf("prod" to listOf("QA", "qa", "staging")), HostTagsCodec.decode(text))
        assertTrue(HostTagsCodec.decode("").isEmpty())
    }

    @Test
    fun `parseTags trims, deduplicates and caps the number of tags`() {
        assertEquals(listOf("dev", "test"), parseTags(" dev , test, dev,, "))
        assertEquals(HostTagsCodec.MAX_PER_HOST, parseTags((1..12).joinToString(",") { "tag$it" }).size)
    }

    @Test
    fun `the tag key is the alias, or host, port and user for a manual target`() {
        assertEquals("prod", SshTarget(host = "prod", alias = "prod").tagKey)
        assertEquals("example.com:2222:me", SshTarget(host = "example.com", user = "me", port = 2222).tagKey)
    }
}
