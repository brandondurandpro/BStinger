package org.example.kotlineditor.unit

import org.example.kotlineditor.ui.LruMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LruMapTest {
    @Test
    fun `keeps at most its capacity, dropping the oldest entry`() {
        val map = LruMap<String, Int>(2)
        map["a"] = 1
        map["b"] = 2
        map["c"] = 3
        assertEquals(listOf("b", "c"), map.keys.toList())
        assertNull(map["a"])
    }

    @Test
    fun `reading an entry makes it the most recent`() {
        val map = LruMap<String, Int>(2)
        map["a"] = 1
        map["b"] = 2
        assertEquals(1, map["a"])
        map["c"] = 3
        assertEquals(listOf("a", "c"), map.keys.toList())
    }

    @Test
    fun `replacing a value makes the entry the most recent without growing the map`() {
        val map = LruMap<String, Int>(2)
        map["a"] = 1
        map["b"] = 2
        map["a"] = 10
        map["c"] = 3
        assertEquals(mapOf("a" to 10, "c" to 3), map)
    }

    @Test
    fun `getOrPut caches the computed value and evicts like a put`() {
        val map = LruMap<String, Int>(1)
        var computed = 0
        val compute = {
            computed++
            computed
        }
        assertEquals(1, map.getOrPut("a", compute))
        assertEquals(1, map.getOrPut("a", compute))
        assertEquals(2, map.getOrPut("b", compute))
        assertEquals(listOf("b"), map.keys.toList())
        assertEquals(2, computed)
    }
}
