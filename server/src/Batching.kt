package org.example.kotlineditor.server

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Groups the elements of this flow into lists of at most [maxSize], each sent at most [maxDelay] after its first
 * element arrived: a slow source still shows its first elements quickly, a fast one does not send one message per
 * element. The upstream runs concurrently and is cancelled when the downstream stops collecting; its failure reaches
 * the collector after the elements that came before it.
 */
fun <T> Flow<T>.batched(maxSize: Int, maxDelay: Duration): Flow<List<T>> {
    require(maxSize > 0) { "maxSize must be positive" }
    val upstream = this
    return flow {
        coroutineScope {
            // A failure travels through the channel, after the elements before it (failing the producer would
            // cancel this scope and lose them).
            val elements =
                upstream
                    .map<T, Element<T>> { Element.Value(it) }
                    .catch { emit(Element.Failure(it)) }
                    .produceIn(this)
            Batcher(elements, maxSize, maxDelay, this@flow).run()
        }
    }
}

private sealed interface Element<out T> {
    class Value<T>(val value: T) : Element<T>

    class Failure(val cause: Throwable) : Element<Nothing>
}

private class Batcher<T>(
    private val elements: ReceiveChannel<Element<T>>,
    private val maxSize: Int,
    private val maxDelay: Duration,
    private val out: FlowCollector<List<T>>,
) {
    private var batch = ArrayList<T>()
    private var started = TimeSource.Monotonic.markNow()

    suspend fun run() {
        var open = true
        while (open) {
            val received = next()
            if (received?.isClosed == true) open = false
            val element = received?.getOrNull() ?: continue
            when (element) {
                is Element.Failure -> {
                    flush()
                    throw element.cause
                }

                is Element.Value -> {
                    if (batch.isEmpty()) started = TimeSource.Monotonic.markNow()
                    batch += element.value
                    if (batch.size >= maxSize) flush()
                }
            }
        }
        flush()
    }

    // The next element, or null (after sending the batch) when the batch waited long enough. `select` takes an
    // element or times out, never both: no element is lost on a timeout.
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun next(): ChannelResult<Element<T>>? {
        val waiting = batch.isNotEmpty()
        val remaining = (maxDelay - started.elapsedNow()).coerceAtLeast(Duration.ZERO)
        val received =
            select {
                elements.onReceiveCatching { it }
                if (waiting) onTimeout(remaining) { null }
            }
        if (received == null) flush()
        return received
    }

    private suspend fun flush() {
        if (batch.isNotEmpty()) {
            val full = batch
            batch = ArrayList()
            out.emit(full)
        }
    }
}
