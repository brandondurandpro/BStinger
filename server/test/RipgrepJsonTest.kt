package org.example.kotlineditor.server

import org.example.kotlineditor.server.ripgrep.JsonLineReader
import org.example.kotlineditor.server.ripgrep.RipgrepEvent
import org.example.kotlineditor.server.ripgrep.RipgrepJson
import org.example.kotlineditor.server.ripgrep.RipgrepMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Parsing of `rg --json` output; the fixtures are real ripgrep 15.2.0 messages. */
class RipgrepJsonTest {
    private fun match(line: String): RipgrepMatch = assertIs<RipgrepEvent.Match>(RipgrepJson.parse(line)).match!!

    @Test
    fun `byte offsets of multi-byte characters become character columns`() {
        // "héllo 日本 needle x needle": é is 2 bytes, 日 and 本 are 3 bytes each.
        val m =
            match(
                """{"type":"match","data":{"path":{"text":"/p/a.txt"},""" +
                    """"lines":{"text":"héllo 日本 needle x needle\r\n"},"line_number":3,"absolute_offset":0,""" +
                    """"submatches":[{"match":{"text":"needle"},"start":14,"end":20},""" +
                    """{"match":{"text":"needle"},"start":23,"end":29}]}}""",
            )
        assertEquals("/p/a.txt", m.path)
        assertEquals(2, m.line)
        assertEquals("héllo 日本 needle x needle", m.text)
        assertEquals(listOf(9 until 15, 18 until 24), m.ranges)
        assertEquals("needle", m.text.substring(m.ranges[1].first, m.ranges[1].last + 1))
    }

    @Test
    fun `characters outside the BMP count as two columns like in the editor`() {
        val m =
            match(
                """{"type":"match","data":{"path":{"text":"/p/e.txt"},"lines":{"text":"😀needle\n"},""" +
                    """"line_number":1,"absolute_offset":0,""" +
                    """"submatches":[{"match":{"text":"needle"},"start":4,"end":10}]}}""",
            )
        assertEquals(listOf(2 until 8), m.ranges)
        assertEquals("needle", m.text.substring(2, 8))
    }

    @Test
    fun `invalid UTF-8 lines arrive base64 encoded and keep consistent columns`() {
        // "bad\xff\xfe needle\n": each invalid byte decodes to one replacement character.
        val m =
            match(
                """{"type":"match","data":{"path":{"text":"/p/bad.txt"},"lines":{"bytes":"YmFk//4gbmVlZGxlCg=="},""" +
                    """"line_number":1,"absolute_offset":0,""" +
                    """"submatches":[{"match":{"text":"needle"},"start":6,"end":12}]}}""",
            )
        val replacement = Char(0xFFFD) // Written as a code: the linter rewrites the escape into the character.
        assertEquals("bad$replacement$replacement needle", m.text)
        assertEquals(listOf(6 until 12), m.ranges)
    }

    @Test
    fun `long lines are complete in JSON output and columns past the preview stay exact`() {
        val text = "é".repeat(600) + "needle" + "z".repeat(100)
        val m =
            match(
                """{"type":"match","data":{"path":{"text":"/p/long.txt"},"lines":{"text":"$text\n"},""" +
                    """"line_number":1,"absolute_offset":0,""" +
                    """"submatches":[{"match":{"text":"needle"},"start":1200,"end":1206}]}}""",
            )
        assertEquals(text, m.text)
        assertEquals(listOf(600 until 606), m.ranges)
    }

    @Test
    fun `other messages are recognised and garbage is ignored`() {
        val begin = """{"type":"begin","data":{"path":{"text":"/p/a.txt"}}}"""
        assertEquals(RipgrepEvent.Begin("/p/a.txt"), RipgrepJson.parse(begin))
        val end =
            """{"type":"end","data":{"path":{"text":"/p/a.txt"},"binary_offset":null,"stats":{"elapsed":""" +
                """{"secs":0,"nanos":63506,"human":"0.000064s"},"searches":1,"matches":2}}}"""
        assertEquals(RipgrepEvent.End("/p/a.txt"), RipgrepJson.parse(end))
        val summary =
            """{"data":{"elapsed_total":{"human":"0.002579s","nanos":2578781,"secs":0},"stats":{"matches":5}},""" +
                """"type":"summary"}"""
        assertEquals(RipgrepEvent.Summary, RipgrepJson.parse(summary))
        assertEquals(RipgrepEvent.Other, RipgrepJson.parse("""{"type":"context","data":{}}"""))
        assertNull(RipgrepJson.parse(""))
        assertNull(RipgrepJson.parse("""{"type":"match","data":{"path":"""))
        assertNull(RipgrepJson.parse("[1, 2]"))
        assertNull(RipgrepJson.parse("""{"no":"type"}"""))
    }

    @Test
    fun `a match in a file whose name is not UTF-8 is reported without content`() {
        val line =
            """{"type":"match","data":{"path":{"bytes":"/w=="},"lines":{"text":"needle\n"},"line_number":1,""" +
                """"absolute_offset":0,"submatches":[{"match":{"text":"needle"},"start":0,"end":6}]}}"""
        assertNull(assertIs<RipgrepEvent.Match>(RipgrepJson.parse(line)).match)
    }

    @Test
    fun `the line reader skips lines longer than its limit`() {
        val input = "short\n${"x".repeat(100)}\nlast without newline".byteInputStream()
        val reader = JsonLineReader(input, maxLineBytes = 50)
        assertEquals("short", reader.readLine())
        assertEquals("", reader.readLine())
        assertEquals("last without newline", reader.readLine())
        assertNull(reader.readLine())
        assertEquals(1, reader.skipped)
    }

    @Test
    fun `the line reader keeps multi-byte characters split across its buffer`() {
        val text = "é".repeat(70_000)
        val reader = JsonLineReader("$text\nnext\n".byteInputStream(), maxLineBytes = 1_000_000)
        assertEquals(text, reader.readLine())
        assertEquals("next", reader.readLine())
        assertNull(reader.readLine())
    }
}
