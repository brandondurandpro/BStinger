package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.example.kotlineditor.telemetry.ClientTelemetry
import org.example.kotlineditor.telemetry.Telemetry
import org.example.kotlineditor.telemetry.telemetryForbidden
import java.util.UUID

/** What the server is told about the telemetry: on as installation [instanceId], or off (null id). */
data class SharedConsent(val enabled: Boolean, val instanceId: String?)

/**
 * Whether the user agreed to share anonymous performance measures. Nothing is measured until they say yes, and
 * `KOTLIN_EDITOR_TELEMETRY=off` keeps it off whatever they say. The choice takes effect at once: on, the IDE
 * measures with what [telemetryOf] makes; off, what was queued is dropped.
 */
@Stable
class TelemetryConsent(
    preferences: Map<String, String>,
    /** Makes the telemetry of installation `instanceId`; null exports nothing (previews, most tests). */
    private val telemetryOf: ((instanceId: String) -> Telemetry)? = null,
    /** Whether the environment forbids telemetry. */
    val forbidden: Boolean = telemetryForbidden(),
) {
    /** The user's answer, or null while they were not asked. */
    var choice: Boolean? by mutableStateOf(preferences[PreferenceKeys.TELEMETRY_ENABLED]?.toBooleanStrictOrNull())
        private set

    /** The random id of this installation, made when the user first agreed. */
    var instanceId: String? by mutableStateOf(preferences[PreferenceKeys.TELEMETRY_INSTANCE])
        private set

    /** Whether the measures are sent. */
    val enabled: Boolean get() = choice == true && !forbidden

    /** Whether the welcome screen asks the user. */
    val asking: Boolean get() = choice == null && !forbidden

    /** What the servers are told. */
    val shared: SharedConsent get() = if (enabled) SharedConsent(true, instanceId) else SharedConsent(false, null)

    init {
        apply()
    }

    /** The user answered, from the banner or the menu. */
    fun choose(agreed: Boolean) {
        if (agreed && instanceId == null) instanceId = UUID.randomUUID().toString()
        choice = agreed
        apply()
    }

    /** The preferences that keep the choice; none until the user answered. */
    fun toPreferences(): Map<String, String> {
        val answer = choice ?: return emptyMap()
        val id = instanceId?.let { mapOf(PreferenceKeys.TELEMETRY_INSTANCE to it) }.orEmpty()
        return mapOf(PreferenceKeys.TELEMETRY_ENABLED to answer.toString()) + id
    }

    private fun apply() {
        val factory = telemetryOf ?: return
        val id = instanceId
        val next = if (enabled && id != null) factory(id) else Telemetry.Disabled
        ClientTelemetry.switchTo(next)
    }
}
