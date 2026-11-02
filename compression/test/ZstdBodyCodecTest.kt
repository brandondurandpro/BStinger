package org.example.kotlineditor.compression

import java.io.IOException
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZstdBodyCodecTest {
    private val code =
        buildString {
            repeat(400) { index ->
                appendLine("    val value$index = compute($index) // commentaire de test")
                appendLine("    if (value$index > 0) println(\"value = \$value$index\")")
            }
        }.encodeToByteArray()

    @Test
    fun `code round trips and uses the dictionary`() {
        ZstdBodyCodec().use { codec ->
            val encoded = assertNotNull(codec.compress(code, code = true))
            assertTrue(codec.dictionaryIdOf(encoded) != 0L, "the dictionary should win on code")
            assertContentEquals(code, codec.decompress(encoded))
        }
    }

    @Test
    fun `metadata is compressed without the dictionary`() {
        ZstdBodyCodec().use { codec ->
            val metadata = (1..2_000).joinToString("\n") { "/src/main/kotlin/File$it.kt" }.encodeToByteArray()
            val encoded = assertNotNull(codec.compress(metadata, code = false))
            assertEquals(0L, codec.dictionaryIdOf(encoded))
            assertContentEquals(metadata, codec.decompress(encoded))
        }
    }

    @Test
    fun `small bodies stay raw`() {
        ZstdBodyCodec().use { codec -> assertNull(codec.compress(ByteArray(32), code = true)) }
    }

    @Test
    fun `incompressible bodies stay raw`() {
        val random = ByteArray(8 * 1024)
        Random(42).nextBytes(random)
        ZstdBodyCodec().use { codec -> assertNull(codec.compress(random, code = true)) }
    }

    @Test
    fun `a frame needing an unknown dictionary is rejected`() {
        val frame = ZstdBodyCodec().use { codec -> assertNotNull(codec.compress(code, code = true)) }
        ZstdBodyCodec(dictionary = null).use { codec ->
            assertFailsWith<IOException> { codec.decompress(frame) }
        }
    }

    @Test
    fun `bodies above the dictionary limit still round trip`() {
        val large = ByteArray(2 shl 20) { index -> ('a' + index % 26).code.toByte() }
        ZstdBodyCodec().use { codec ->
            val encoded = assertNotNull(codec.compress(large, code = true))
            assertEquals(0L, codec.dictionaryIdOf(encoded))
            assertContentEquals(large, codec.decompress(encoded))
        }
    }
}
