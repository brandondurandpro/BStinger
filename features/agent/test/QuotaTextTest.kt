package org.example.kotlineditor.state

import kotlin.test.Test
import kotlin.test.assertEquals

class QuotaTextTest {
    @Test
    fun `the weekly limit of the status of Codex is read`() {
        val text =
            """
            Account: ChatGPT free (zermelwall@gmail.com)
            Context window: 5% used (13.2K used / 258.4K)
            codex Weekly limit: 100% left (resets 13:01 on 8 déc.)
            """.trimIndent()
        val expected = QuotaLimit("codex Weekly limit", 100, remaining = true, reset = "resets 13:01 on 8 déc.")
        assertEquals(listOf(expected), quotaLimitsOf(text))
        assertEquals(0, expected.used)
    }

    @Test
    fun `the limits of the usage of Claude are read, the bars and the tables are not`() {
        val text =
            """
            Limits
            5-hour limit — 28% · Resets Nov 8, 2:09 PM GMT+1
            ██████░░░░░░░░░░░░░░
            Weekly · all models — 12% · Resets Nov 14, 4:59 PM GMT+1
            | Cost | API time | Active |
            93% Stop sessions you no longer need · some ran 8 hours or more
            """.trimIndent()
        val limits = quotaLimitsOf(text)
        assertEquals(listOf("5-hour limit", "Weekly · all models"), limits.map { it.label })
        assertEquals(listOf(28, 12), limits.map { it.used })
        assertEquals("28% used", limits.firstOrNull()?.text)
    }

    @Test
    fun `a text without a quota gives nothing`() {
        assertEquals(emptyList(), quotaLimitsOf("Hello, here is the code you asked for."))
    }

    @Test
    fun `markdown around the lines of Claude does not hide them`() {
        val text = "- **5-hour limit** — 28% · Resets Nov 8, 2:09 PM GMT+1\n* `Weekly · all models` — 12% · Resets Nov 14"
        assertEquals(listOf(28, 12), quotaLimitsOf(text).map { it.used })
    }
}
