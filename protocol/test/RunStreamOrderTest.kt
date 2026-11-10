package org.example.kotlineditor.rpc

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.RunChunk
import kotlin.test.Test
import kotlin.test.assertEquals

class RunStreamOrderTest {
    private fun event(seq: Long): JsonObject =
        buildJsonObject {
            put("seq", seq)
            put("chunk", RunChunk(seq, listOf("line $seq")).toJson())
        }

    private fun drain(channel: Channel<RunChunk>): List<Long> {
        val seqs = ArrayList<Long>()
        while (true) {
            val chunk = channel.tryReceive().getOrNull() ?: return seqs
            seqs += chunk.seq
        }
    }

    @Test
    fun `a live chunk that overtakes the replay waits for the chunks before it`() {
        val streams = StreamRegistry()
        val channel = streams.openRun(1)
        streams.onEvent(1, event(1))
        // the link was silent: chunk 20 arrives live before the replay of 2..19
        streams.onEvent(1, event(20))
        assertEquals(listOf(1L), drain(channel))
        (2L..19L).forEach { streams.onEvent(1, event(it)) }
        assertEquals((2L..20L).toList(), drain(channel))
        assertEquals(20L, streams.resumes().single().lastSeq)
    }

    @Test
    fun `a chunk the server no longer keeps does not hold the others back at the end`() {
        val streams = StreamRegistry()
        val channel = streams.openRun(1)
        streams.onEvent(1, event(1))
        streams.onEvent(1, event(5))
        streams.onEvent(1, event(6))
        streams.onEnd(1, buildJsonObject { put("seq", 6) })
        assertEquals(listOf(1L, 5L, 6L), drain(channel))
    }

    @Test
    fun `a chunk received twice is delivered once`() {
        val streams = StreamRegistry()
        val channel = streams.openRun(1)
        streams.onEvent(1, event(1))
        streams.onEvent(1, event(1))
        streams.onEvent(1, event(2))
        assertEquals(listOf(1L, 2L), drain(channel))
    }
}
