package org.example.kotlineditor.e2e

import java.lang.management.ManagementFactory
import kotlin.test.assertTrue

private const val NANOS_PER_MILLI = 1_000_000L
private const val BYTES_PER_MB = 1_048_576L
private const val NO_BUDGET = Long.MAX_VALUE

/** What a measured block cost: wall-clock time and bytes allocated by the test thread. */
class Cost(val nanos: Long, val bytes: Long) {
    val millis: Long get() = nanos / NANOS_PER_MILLI
    val megabytes: Long get() = bytes / BYTES_PER_MB

    operator fun minus(other: Cost) = Cost(nanos - other.nanos, bytes - other.bytes)
}

private val allocatedBytes: Long
    get() = (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).totalThreadAllocatedBytes

/** What running [block] costs, without checking any budget. */
fun costOf(block: () -> Unit): Cost {
    val bytes = allocatedBytes
    val start = System.nanoTime()
    block()
    return Cost(System.nanoTime() - start, allocatedBytes - bytes)
}

/**
 * Prints [cost] as a `PERF` line and fails if it is over [budgetMillis] or [budgetMegabytes] (allocated, not
 * retained). Budgets are several times what a developer machine measures, so that a slow CI runner passes but an
 * algorithmic regression (a linear scan per frame, a quadratic rebuild, a layout per frame) fails.
 */
fun report(label: String, cost: Cost, budgetMillis: Long, budgetMegabytes: Long = NO_BUDGET): Cost {
    val memoryBudget = if (budgetMegabytes == NO_BUDGET) "" else ", $budgetMegabytes MB"
    println(
        "PERF ${label.padEnd(50)} ${cost.millis} ms, ${cost.megabytes} MB allocated " +
            "(budget $budgetMillis ms$memoryBudget)",
    )
    assertTrue(cost.millis <= budgetMillis, "$label took ${cost.millis} ms, budget $budgetMillis ms")
    assertTrue(cost.megabytes <= budgetMegabytes, "$label allocated ${cost.megabytes} MB, budget $budgetMegabytes MB")
    return cost
}

/** Runs [block] and [report]s its cost. */
fun measure(label: String, budgetMillis: Long, budgetMegabytes: Long = NO_BUDGET, block: () -> Unit): Cost =
    report(label, costOf(block), budgetMillis, budgetMegabytes)
