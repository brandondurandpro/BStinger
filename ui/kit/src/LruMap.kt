package org.example.kotlineditor.ui

class LruMap<K, V>(private val capacity: Int) : LinkedHashMap<K, V>(capacity, LOAD_FACTOR, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > capacity

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}
