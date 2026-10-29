package org.example.kotlineditor.rpc

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/** Requests sent and not answered yet: kept until answered so they can be sent again after a reconnection. */
internal class PendingRequests {
    private val requests = ConcurrentHashMap<Long, Pending>()

    class Pending(val frame: Frame) {
        val result = CompletableDeferred<Frame>()
    }

    fun register(frame: Frame): Pending = Pending(frame).also { requests[frame.id] = it }

    fun remove(id: Long) {
        requests.remove(id)
    }

    fun complete(answer: Frame) {
        requests[answer.id]?.result?.complete(answer)
    }

    /** Every unanswered request, in the order it was first sent (ids increase). */
    fun inOrder(): List<Frame> = requests.entries.sortedBy { it.key }.map { it.value.frame }

    fun failAll(cause: Throwable) = requests.values.forEach { it.result.completeExceptionally(cause) }
}
