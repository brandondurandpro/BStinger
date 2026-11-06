package org.example.kotlineditor.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FindOptions
import org.example.kotlineditor.model.SearchBatch
import org.example.kotlineditor.model.SearchEngine
import org.example.kotlineditor.model.TextMatch
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.long
import org.example.kotlineditor.rpc.searchBatch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val ROOT = FileEntry("/p", "p", true)
private val QUERY = FindOptions(text = "needle")
private const val STREAM_ID = 42L
private const val TIMEOUT = 5_000L

private fun hit(line: Int) = TextMatch(FileEntry("/p/a.txt", "a.txt", false), line, 0, "needle here")

// A search of the registry: [count] batches of [perBatch] hits (one per line), then the batch that closes it.
private fun searchOf(count: Int, perBatch: Int, pauseMillis: Long = 0): Flow<SearchBatch> =
    flow {
        for (seq in 1..count) {
            if (pauseMillis > 0) delay(pauseMillis)
            val hits = (0 until perBatch).map(::hit)
            emit(SearchBatch(seq.toLong(), hits, done = false, total = seq * perBatch, engine = null))
        }
        val done =
            SearchBatch(
                (count + 1).toLong(),
                emptyList(),
                done = true,
                total = count * perBatch,
                engine = SearchEngine.BuiltIn,
            )
        emit(done)
    }

/** What the registry pushed, in order; each Event frame's batch and each End frame's seq at hand. */
private class Recorder {
    val frames = CopyOnWriteArrayList<Frame>()

    operator fun invoke(frame: Frame) {
        frames += frame
    }

    fun batches(): List<SearchBatch> =
        frames
            .filter { it.kind == FrameKind.Event }
            .mapNotNull(::batchOf)

    // The batch behind an event frame, null for the frames that carry something else than a batch.
    private fun batchOf(frame: Frame): SearchBatch? {
        val batch = frame.payload.jsonObject.get("batch") ?: return null
        return batch.searchBatch()
    }

    fun endSeqs(): List<Long> =
        frames
            .filter { it.kind == FrameKind.End }
            .map { it.payload.jsonObject.long("seq") }

    fun ends(): Boolean = frames.count { it.kind == FrameKind.End } > 0
}

/** The keeper of a session's Find in Files streams: what it pushes, what a reconnected client gets again. */
class SearchRegistryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recorded = Recorder()
    private val registry = SearchRegistry(scope, recorded::invoke)

    @AfterTest
    fun cleanup() {
        registry.stopAll()
        scope.cancel()
    }

    // Waits, up to a sane delay, until [condition] holds: the pushes of the search arrive on a coroutine.
    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(TIMEOUT) {
            while (!condition()) {
                delay(5)
            }
        }
    }

    @Test
    fun `a search streams its batches numbered and ends with the total and the engine`() =
        runBlocking {
            registry.start(STREAM_ID, ROOT, QUERY, 100) { searchOf(count = 3, perBatch = 2) }
            awaitUntil { recorded.ends() }
            val batches = recorded.batches()
            assertEquals((1L..batches.size).toList(), batches.map { it.seq })
            assertEquals(listOf(false, false, false, true), batches.map { it.done })
            assertEquals(6, batches.last().total)
            assertEquals(SearchEngine.BuiltIn, batches.last().engine)
            assertEquals(listOf(4L), recorded.endSeqs())
        }

    @Test
    fun `a resume after k replays k+1 onwards and the end, without repeating k`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            registry.start(STREAM_ID, ROOT, QUERY, 100) {
                flow {
                    emit(SearchBatch(1, listOf(hit(0)), done = false, total = 1, engine = null))
                    emit(SearchBatch(2, listOf(hit(1)), done = false, total = 2, engine = null))
                    gate.await()
                    emit(SearchBatch(3, listOf(hit(2)), done = false, total = 3, engine = null))
                    emit(SearchBatch(4, emptyList(), done = true, total = 3, engine = SearchEngine.BuiltIn))
                }
            }
            awaitUntil { recorded.batches().size == 2 }
            // The client reconnects having received the batch 1 only: the ones after 1 come again, in order.
            registry.resume(STREAM_ID, 1)
            assertEquals(listOf(1L, 2L, 2L), recorded.batches().map { it.seq })
            gate.complete(Unit)
            awaitUntil { recorded.ends() }
            // Nothing repeated of what arrived after: 3 once, the closing one, and the End carrying the last seq.
            assertEquals(listOf(1L, 2L, 2L, 3L, 4L), recorded.batches().map { it.seq })
            assertEquals(listOf(4L), recorded.endSeqs())
        }

    @Test
    fun `a resume of a stream that already ended replays the batches it missed`() =
        runBlocking {
            registry.start(STREAM_ID, ROOT, QUERY, 100) { searchOf(count = 2, perBatch = 1) }
            awaitUntil { recorded.ends() }
            // The client lost everything after the batch 1 (with the end): the server has the frames buffered.
            registry.resume(STREAM_ID, 1)
            assertEquals(listOf(1L, 2L, 3L, 2L, 3L), recorded.batches().map { it.seq })
            assertEquals(listOf(3L, 3L), recorded.endSeqs())
        }

    @Test
    fun `a search stopped by the session gets no further batch, its flow cancelled`() =
        runBlocking {
            val cancelled = CompletableDeferred<Unit>()
            registry.start(STREAM_ID, ROOT, QUERY, 100) {
                flow {
                    emit(SearchBatch(1, listOf(hit(0)), done = false, total = 1, engine = null))
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            }
            awaitUntil { recorded.batches().size == 1 }
            registry.stop(STREAM_ID)
            recorded.frames.clear()
            awaitUntil { cancelled.isCompleted }
            registry.resume(STREAM_ID, 0)
            assertTrue(recorded.frames.isEmpty(), "a stopped stream has nothing to answer")
        }

    @Test
    fun `a resume for a stream the server does not have answers nothing`() {
        registry.resume(STREAM_ID, 0)
        assertTrue(recorded.frames.isEmpty())
    }
}
