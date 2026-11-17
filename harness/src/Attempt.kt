package org.example.kotlineditor.harness

import kotlinx.coroutines.CancellationException

/**
 * The result of [block], or what [onFailure] makes of the exception it threw. A cancellation is not a failure and is
 * thrown again. The agents of a team break in many ways (a refusal, a dead process, a bad path): whatever one of them
 * throws must become a report to the manager or a notice, never end the harness or the other agents.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> attempt(onFailure: (Exception) -> T, block: suspend () -> T): T =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onFailure(failure)
    }

/** What [this] says, the name of its class when it says nothing. */
internal fun Exception.reason(): String = message ?: this::class.java.simpleName
