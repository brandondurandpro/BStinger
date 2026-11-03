package org.example.kotlineditor.telemetry

import kotlin.coroutines.cancellation.CancellationException

/** How an operation ended. */
sealed interface Outcome {
    /** The `outcome` label. */
    val label: String

    /** It did what was asked. */
    data object Ok : Outcome {
        override val label = "ok"
    }

    /** It was no longer wanted: a newer query, a closed popup, a cancelled coroutine. Not an error. */
    data object Cancelled : Outcome {
        override val label = "cancelled"
    }

    /** It failed with an exception of class [type]: its simple name, never its message (it may hold a path). */
    data class Error(val type: String) : Outcome {
        override val label = "error"
    }

    companion object {
        /** The outcome of [failure]: [Cancelled] for a cancellation, else an [Error] of its class. */
        fun of(failure: Throwable): Outcome =
            if (failure is CancellationException) Cancelled else Error(failure::class.simpleName ?: "Throwable")
    }
}
