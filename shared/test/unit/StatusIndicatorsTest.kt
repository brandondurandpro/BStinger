package org.example.kotlineditor.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusIndicatorsTest {
    @Test
    fun `everything is shown when nothing was saved`() {
        val indicators = StatusIndicators()
        assertTrue(StatusIndicator.entries.all(indicators::isShown))
        assertEquals("", indicators.toPreferences().getValue(PreferenceKeys.STATUS_HIDDEN))
    }

    @Test
    fun `the hidden ones are read back in the order of the menu`() {
        val indicators = StatusIndicators(mapOf(PreferenceKeys.STATUS_HIDDEN to "Encoding, Cpu"))
        assertFalse(indicators.isShown(StatusIndicator.Cpu))
        assertFalse(indicators.isShown(StatusIndicator.Encoding))
        assertTrue(indicators.isShown(StatusIndicator.Memory))
        assertEquals("Cpu,Encoding", indicators.toPreferences().getValue(PreferenceKeys.STATUS_HIDDEN))
    }

    @Test
    fun `a name this version does not know is ignored and dropped`() {
        val indicators = StatusIndicators(mapOf(PreferenceKeys.STATUS_HIDDEN to "Gone,,Memory"))
        assertFalse(indicators.isShown(StatusIndicator.Memory))
        assertEquals("Memory", indicators.toPreferences().getValue(PreferenceKeys.STATUS_HIDDEN))
    }

    @Test
    fun `showing a hidden one again forgets it`() {
        val indicators = StatusIndicators()
        indicators.setShown(StatusIndicator.Caret, false)
        indicators.setShown(StatusIndicator.Caret, false)
        assertEquals("Caret", indicators.toPreferences().getValue(PreferenceKeys.STATUS_HIDDEN))
        indicators.setShown(StatusIndicator.Caret, true)
        assertEquals("", indicators.toPreferences().getValue(PreferenceKeys.STATUS_HIDDEN))
    }
}
