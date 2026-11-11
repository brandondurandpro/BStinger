package org.example.kotlineditor.unit

import org.example.kotlineditor.state.FileIndex
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuzzyMatchTest {
    @Test
    fun `matches characters in order`() {
        assertNotNull(FileIndex.fuzzyMatch("strkt", "Strings.kt"))
        assertNull(FileIndex.fuzzyMatch("tks", "Strings.kt"))
    }

    @Test
    fun `camel hump and prefix matches score higher`() {
        val hump = FileIndex.fuzzyMatch("ES", "EditorState.kt")!!.first
        val scattered = FileIndex.fuzzyMatch("ES", "ExpressionsUtils.kt")!!.first
        assertTrue(hump >= scattered)
        val exact = FileIndex.fuzzyMatch("App", "App.kt")!!.first
        val inner = FileIndex.fuzzyMatch("App", "MyApplication.kt")!!.first
        assertTrue(exact > inner)
    }
}
