package org.example.kotlineditor.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusIndicatorsTest {
    @Test
    fun `everything is shown when nothing was saved`() {
        val indicators = StatusIndicators()
        assertTrue("Caret" !in indicators.toPreferences().getValue(PreferenceKeys.STATUS_HIDDEN))
        assertEquals("", indicators.toPreferences().getValue(PreferenceKeys.STATUS_HIDDEN))
    }

    @Test
    fun `the hidden ones are read back in the order of the menu`() {
        val indicators = StatusIndicators(mapOf(PreferenceKeys.STATUS_HIDDEN to "Encoding, Cpu"))
        assertFalse(indicators.isShown("Cpu"))
        assertFalse(indicators.isShown("Encoding"))
        assertTrue(indicators.isShown("Memory"))
        assertEquals("Cpu,Encoding", indicators.toPreferences().getValue(PreferenceKeys.STATUS_HIDDEN))
    }

    @Test
    fun `a key this version does not know is kept, for the indicator to come back`() {
        val indicators = StatusIndicators(mapOf(PreferenceKeys.STATUS_HIDDEN to "Gone,,Memory"))
        assertFalse(indicators.isShown("Memory"))
        assertFalse(indicators.isShown("Gone"))
        assertEquals("Gone,Memory", indicators.toPreferences().getValue(PreferenceKeys.STATUS_HIDDEN))
    }

    @Test
    fun `showing a hidden one again forgets it`() {
        val indicators = StatusIndicators()
        indicators.setShown("Caret", false)
        indicators.setShown("Caret", false)
        assertEquals("Caret", indicators.toPreferences().getValue(PreferenceKeys.STATUS_HIDDEN))
        indicators.setShown("Caret", true)
        assertEquals("", indicators.toPreferences().getValue(PreferenceKeys.STATUS_HIDDEN))
    }
}
