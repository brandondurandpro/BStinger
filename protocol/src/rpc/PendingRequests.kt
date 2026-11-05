package org.example.kotlineditor.rpc

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

// The connection number of a request not sent yet: connections are numbered from 1.
private const val NOT_SENT = 0L

/** Requests sent and not answered yet: kept until answered so they can be sent again after a reconnection. */
internal class PendingRequests {
    private val requests = ConcurrentHashMap<Long, Pending>()

    /**
     * One request waiting for its answer. The writer of the connection [claim]s it before writing it, the caller
     * [abandon]s it when it stops waiting: once abandoned it is never written again.
     */
    class Pending(val frame: Frame) {
        val result = CompletableDeferred<Frame>()

        // The connection the request was last written on; guarded by this.
        private var sentOn = NOT_SENT

        // The caller stopped waiting (answered, timed out, cancelled, failed); guarded by this.
        private var abandoned = false

        /** Times the request was sent: more than once when it survived a reconnection. */
        @Volatile var sends = 0
            private set

        /** Whether it still has to be written on [connection]: not answered, not abandoned, not written there yet. */
        @Synchronized
        fun wanted(connection: Long): Boolean = !abandoned && !result.isCompleted && sentOn != connection

        /** Reserves the request for a write on [connection]; false when it must not be written there. */
        @Synchronized
        fun claim(connection: Long): Boolean {
            val writes = wanted(connection)
            if (writes) {
                sentOn = connection
                sends++
            }
            return writes
        }

        /** Stops any later write of the request; returns whether it was written (or is being written) before. */
        @Synchronized
        fun abandon(): Boolean {
            abandoned = true
            return sends > 0
        }
    }

    fun register(frame: Frame): Pending = Pending(frame).also { requests[frame.id] = it }

    fun remove(id: Long) {
        requests.remove(id)
    }

    fun complete(answer: Frame) {
        requests[answer.id]?.result?.complete(answer)
    }

    /** Every unanswered request, in the order it was first sent (ids increase). */
    fun inOrder(): List<Pending> =
        requests.entries
            .sortedBy { it.key }
            .map { it.value }
            .filterNot { it.result.isCompleted }

    fun failAll(cause: Throwable) = requests.values.forEach { it.result.completeExceptionally(cause) }
}
