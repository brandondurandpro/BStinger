package org.example.kotlineditor.unit

import org.example.kotlineditor.state.AppVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionTest {
    private fun version(text: String): AppVersion = AppVersion.parse(text) ?: error("not a version: $text")

    @Test
    fun `versions compare number by number, not as text`() {
        assertTrue(version("1.10.0") > version("1.9.0"))
        assertTrue(version("v2") > version("1.99.99"))
        assertEquals(version("1.2"), version("v1.2.0"))
        assertEquals(version("1.2").hashCode(), version("1.2.0").hashCode())
    }

    @Test
    fun `a pre-release is older than its release`() {
        assertTrue(version("1.2.3-rc1") < version("1.2.3"))
        assertTrue(version("1.2.3-rc1") < version("1.2.3-rc2"))
        assertTrue(version("1.2.3-rc2") < version("1.2.3-rc10"))
        assertTrue(version("1.2.3-rc9") > version("1.2.2"))
        assertTrue(version("1.2.3-beta") < version("1.2.3-rc1"))
        assertTrue(version("1.2.3-rc1") < version("1.2.3-rc1.1"))
    }

    @Test
    fun `what is not a version is refused`() {
        listOf("", "latest", "v", "1..2", "1.2.3.4.5", " v1.2", "v1.2 ", "1.2/../x", "99999999999", "1.2-").forEach {
            assertNull(AppVersion.parse(it), "'$it'")
        }
    }

    @Test
    fun `the text, the numbers and the suffix are readable`() {
        val tag = version("v1.2.3-rc1")
        assertEquals("v1.2.3-rc1", tag.text)
        assertEquals(listOf(1, 2, 3), tag.numbers)
        assertEquals("rc1", tag.preRelease)
        assertTrue(tag.isPreRelease)
        val release = version("2.0")
        assertEquals("2.0", release.text)
        assertEquals(listOf(2, 0), release.numbers)
        assertNull(release.preRelease)
        assertFalse(release.isPreRelease)
    }

    @Test
    fun `it is shown without the v of a tag`() {
        assertEquals("1.2.3-rc1", version("v1.2.3-rc1").toString())
        assertEquals("1.2", version("1.2").toString())
    }

    @Test
    fun `equal versions may be written differently, others are not equal`() {
        assertEquals(version("1.2"), version("v1.2.0.0"))
        assertNotEquals(version("1.2"), version("1.2-rc1"))
        assertNotEquals(version("1.2"), version("1.3"))
        assertFalse(version("1.2").equals("1.2"))
    }

    @Test
    fun `equal versions have the same hash`() {
        assertEquals(version("1.2").hashCode(), version("v1.2.0.0").hashCode())
        assertEquals(version("1.2-rc1"), version("1.2-rc01"))
        assertEquals(version("1.2-rc1").hashCode(), version("1.2-rc01").hashCode())
    }
}
