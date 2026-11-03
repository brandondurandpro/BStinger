package org.example.kotlineditor.e2e

import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.telemetry.FinishedSpan
import org.example.kotlineditor.telemetry.HookedTelemetry
import org.example.kotlineditor.telemetry.InMemoryHook
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.Side
import org.example.kotlineditor.telemetry.Telemetry
import org.example.kotlineditor.telemetry.TelemetryHook

/**
 * What an e2e app measured, once the user agreed: the spans of the UI ([client]) and of the server ([serverSpans]),
 * kept in memory instead of being exported.
 */
class TelemetryCapture(
    /** Other hooks of the UI, such as a real OpenTelemetry export to a fake collector. */
    exports: List<TelemetryHook> = emptyList(),
) {
    private val clientHook = InMemoryHook()

    /** The telemetry the UI measures with when the user agreed. */
    val client = HookedTelemetry(listOf(clientHook) + exports, Side.Client)

    /** What the server measures with when the client passed the consent on. */
    val serverHook = InMemoryHook()

    /** What the app measures with once the user agreed, whatever installation id. */
    val factory: (String) -> Telemetry = { _ -> client }

    /** The spans the UI ended. */
    val clientSpans: List<FinishedSpan> get() = clientHook.spans

    /** The spans the server ended. */
    val serverSpans: List<FinishedSpan> get() = serverHook.spans

    /** Every span of both sides. */
    val all: List<FinishedSpan> get() = clientSpans + serverSpans

    /** The UI's spans of [operation]. */
    fun clientSpansOf(operation: Operation): List<FinishedSpan> = clientSpans.filter { it.operation == operation }

    /** Forgets what was measured so far. */
    fun clear() {
        clientHook.clear()
        serverHook.clear()
    }

    companion object {
        /** A fixed installation id: a test can check it went to the server and nowhere else. */
        const val INSTANCE_ID = "00000000-0000-4000-8000-000000000e2e"

        /** Preferences of a user who already agreed. */
        fun agreed(): InMemoryPreferenceStore =
            InMemoryPreferenceStore(
                mapOf(PreferenceKeys.TELEMETRY_ENABLED to "true", PreferenceKeys.TELEMETRY_INSTANCE to INSTANCE_ID),
            )

        /** Preferences of a user who refused. */
        fun refused(): InMemoryPreferenceStore =
            InMemoryPreferenceStore(mapOf(PreferenceKeys.TELEMETRY_ENABLED to "false"))
    }
}
