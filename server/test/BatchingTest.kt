package org.example.kotlineditor.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Grouping streamed search results into messages. */
class BatchingTest {
    @Test
    fun `a fast source is cut into batches of the maximum size, nothing lost or reordered`() =
        runBlocking {
            val batches = (1..1_005).asFlow().batched(100, 10.seconds).toList()
            assertEquals(List(10) { 100 } + 5, batches.map { it.size })
            assertEquals((1..1_005).toList(), batches.flatten())
        }

    @Test
    fun `a slow source sends what it has once the delay is over`() =
        runBlocking {
            val first =
                withTimeout(5_000) {
                    flow {
                        emit(1)
                        emit(2)
                        awaitCancellation() // the source never ends nor fills a batch
                    }.batched(100, 20.milliseconds).first()
                }
            assertEquals(listOf(1, 2), first)
        }

    @Test
    fun `slow elements are all delivered across timeouts`() =
        runBlocking {
            val batches =
                flow {
                    repeat(30) {
                        delay(3)
                        emit(it)
                    }
                }.batched(7, 5.milliseconds).toList()
            assertEquals((0 until 30).toList(), batches.flatten())
            assertTrue(batches.all { it.isNotEmpty() && it.size <= 7 })
        }

    @Test
    fun `stopping the collection stops the source`() =
        runBlocking {
            val stopped = CompletableDeferred<Throwable?>()
            flow {
                var i = 0
                while (true) emit(i++)
            }.onCompletion { stopped.complete(it) }
                .batched(10, 1.seconds)
                .first()
            assertTrue(withTimeout(5_000) { stopped.await() } != null, "the source was cancelled")
        }

    @Test
    fun `a failure of the source reaches the collector after the batches before it`() =
        runBlocking {
            val received = ArrayList<List<Int>>()
            assertFailsWith<IOException> {
                flow {
                    repeat(25) { emit(it) }
                    throw IOException("broken")
                }.batched(10, 1.seconds).collect { received += it }
            }
            assertEquals((0 until 25).toList(), received.flatten())
        }
}
