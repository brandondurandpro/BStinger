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
        assertEquals(listOf("codex Weekly limit: 100% left · (resets 13:01 on 8 déc.)"), quotaLinesOf(text))
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
        assertEquals(
            listOf(
                "5-hour limit: 28% used · Resets Nov 8, 2:09 PM GMT+1",
                "Weekly · all models: 12% used · Resets Nov 14, 4:59 PM GMT+1",
            ),
            quotaLinesOf(text),
        )
    }

    @Test
    fun `a text without a quota gives nothing`() {
        assertEquals(emptyList(), quotaLinesOf("Hello, here is the code you asked for."))
    }
}
