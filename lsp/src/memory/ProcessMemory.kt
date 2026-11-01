package org.example.kotlineditor.lsp.memory

/**
 * The memory of one process, in bytes.
 *
 * [privateBytes] is the memory no other process shares: the anonymous resident pages on Linux, the private
 * commit charge on Windows. It is the metric a leak shows in, and the one the sampler sums over a process
 * tree, because shared libraries would hide a leak or double-count it when several servers load the same
 * ones. [residentBytes] is the whole resident set, shown next to it for context.
 */
data class ProcessMemory(
    /** The memory of the process that no other process shares. */
    val privateBytes: Long,
    /** The whole resident set of the process. */
    val residentBytes: Long,
)
