package org.example.kotlineditor.telemetry

/** The environment variable that turns telemetry off whatever the user chose (CI, companies). */
const val TELEMETRY_VARIABLE = "KOTLIN_EDITOR_TELEMETRY"

private val OFF = setOf("off", "false", "0", "no")

/** Whether [environment] forbids telemetry (`KOTLIN_EDITOR_TELEMETRY=off`). */
fun telemetryForbidden(environment: Map<String, String> = System.getenv()): Boolean {
    val value = environment[TELEMETRY_VARIABLE] ?: return false
    return value.trim().lowercase() in OFF
}
