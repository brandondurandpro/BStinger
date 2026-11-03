package org.example.kotlineditor.state

import org.example.kotlineditor.telemetry.ClientTelemetry
import org.example.kotlineditor.telemetry.InMemoryTelemetry
import org.example.kotlineditor.telemetry.Telemetry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TelemetryConsentTest {
    private val measured = InMemoryTelemetry()
    private val agreed =
        mapOf(PreferenceKeys.TELEMETRY_ENABLED to "true", PreferenceKeys.TELEMETRY_INSTANCE to "id-1")

    @AfterTest
    fun cleanup() = ClientTelemetry.switchTo(Telemetry.Disabled)

    @Test
    fun `absent means not asked yet, which measures nothing`() {
        val consent = TelemetryConsent(emptyMap(), { measured }, forbidden = false)
        assertTrue(consent.asking)
        assertFalse(consent.enabled)
        assertEquals(SharedConsent(false, null), consent.shared)
        assertEquals(emptyMap(), consent.toPreferences())
        assertFalse(ClientTelemetry.isEnabled)
    }

    @Test
    fun `a yes from an earlier run measures from the start, as the same installation`() {
        val consent = TelemetryConsent(agreed, { measured }, forbidden = false)
        assertSame(measured, ClientTelemetry.current)
        assertEquals(SharedConsent(true, "id-1"), consent.shared)
    }

    @Test
    fun `the environment forbids it whatever the user said, and the question is not asked`() {
        val consent = TelemetryConsent(agreed, { measured }, forbidden = true)
        assertFalse(consent.enabled)
        assertFalse(consent.asking)
        assertFalse(ClientTelemetry.isEnabled)
        assertEquals(SharedConsent(false, null), consent.shared)
    }

    @Test
    fun `a no keeps no id, a later yes makes one, and a no again switches off at once`() {
        val consent = TelemetryConsent(emptyMap(), { measured }, forbidden = false)
        consent.choose(false)
        assertNull(consent.instanceId)
        assertEquals(mapOf(PreferenceKeys.TELEMETRY_ENABLED to "false"), consent.toPreferences())
        consent.choose(true)
        val id = checkNotNull(consent.instanceId)
        assertTrue(ClientTelemetry.isEnabled)
        consent.choose(false)
        assertFalse(ClientTelemetry.isEnabled)
        assertEquals(id, consent.toPreferences()[PreferenceKeys.TELEMETRY_INSTANCE])
    }
}
