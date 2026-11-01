package org.example.kotlineditor.lsp.memory

/**
 * One sample of the memory of a tracked tree, at [timeMillis] (the sampler's clock): the sum of the private
 * and resident bytes of the processes that could be read, and how many of them were.
 */
data class MemorySample(
    /** When the sample was taken, in the sampler clock's milliseconds. */
    val timeMillis: Long,
    /** The private memory of every process of the tree, summed. */
    val privateBytes: Long,
    /** The resident memory of every process of the tree, summed. */
    val residentBytes: Long,
    /** How many processes of the tree contributed to the sums. */
    val processCount: Int,
)

/**
 * The memory history of one tracked tree: a ring buffer of [capacity] samples — one hour at the sampler's
 * default interval of 2 s — in primitive arrays, so a history costs about 30 KB and no object per sample
 * until it is read out.
 *
 * Written by the tree's one sampling coroutine and read from anywhere: the lock only has to keep [snapshot]
 * consistent with a write in flight, the readers display, they do not reason about ordering.
 */
class MemoryHistory(
    /** How many samples are kept; the oldest is dropped once more than that were taken. */
    val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val times = LongArray(capacity)
    private val privateBytes = LongArray(capacity)
    private val residentBytes = LongArray(capacity)
    private val processCounts = IntArray(capacity)

    // Index the next sample is written at: over the oldest one once the buffer is full.
    private var next = 0
    private var stored = 0

    /** How many samples are kept, at most [capacity]. */
    val size: Int
        get() = synchronized(this) { stored }

    /** The most recent sample, or null when none was ever taken. */
    val latest: MemorySample?
        get() = synchronized(this) { if (stored == 0) null else sampleAt(stored - 1) }

    /**
     * The sample with the highest private memory — the metric a leak verdict is based on — at the first
     * moment it was reached, or null when none was ever taken.
     */
    val peak: MemorySample?
        get() =
            synchronized(this) {
                var best: MemorySample? = null
                for (position in 0 until stored) {
                    val candidate = sampleAt(position)
                    if (best == null || candidate.privateBytes > best.privateBytes) best = candidate
                }
                best
            }

    /** Appends one sample, dropping the oldest one when the buffer is already full. */
    fun add(timeMillis: Long, privateBytes: Long, residentBytes: Long, processCount: Int) {
        synchronized(this) {
            times[next] = timeMillis
            this.privateBytes[next] = privateBytes
            this.residentBytes[next] = residentBytes
            processCounts[next] = processCount
            if (stored < capacity) stored++
            next = (next + 1) % capacity
        }
    }

    /** The samples in order, oldest first; a fresh list, so its reader owns what it gets. */
    fun snapshot(): List<MemorySample> =
        synchronized(this) {
            val samples = ArrayList<MemorySample>(stored)
            for (position in 0 until stored) {
                samples += sampleAt(position)
            }
            samples
        }

    // The sample [position] steps after the oldest one; the caller holds the lock.
    private fun sampleAt(position: Int): MemorySample {
        // Until the buffer is full the oldest sample is at 0; afterwards it is the one the next write replaces.
        val oldest = if (stored < capacity) 0 else next
        val index = (oldest + position) % capacity
        return MemorySample(times[index], privateBytes[index], residentBytes[index], processCounts[index])
    }

    /** Defaults. */
    companion object {
        /** One hour of samples at the sampler's default interval of 2 s. */
        const val DEFAULT_CAPACITY = 1800
    }
}
