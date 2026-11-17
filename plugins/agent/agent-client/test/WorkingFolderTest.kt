package org.example.kotlineditor.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The folder of a conversation: typed by the user, under the project's root, on a server that writes `/` or `\`. */
class WorkingFolderTest {
    private val unix = "/home/me/project"
    private val windows = "C:\\Users\\me\\project"

    @Test
    fun `an empty field is the root, a relative path is under it`() {
        assertEquals(unix, resolveFolder(unix, ""))
        assertEquals(unix, resolveFolder(unix, "   "))
        assertEquals("$unix/backend/api", resolveFolder(unix, "backend/api"))
        assertEquals("$unix/backend", resolveFolder(unix, "./backend/"))
    }

    @Test
    fun `an absolute path is kept, with its dots resolved`() {
        assertEquals("/etc/ssl", resolveFolder(unix, "/etc/ssl/"))
        assertEquals("/home/me/other", resolveFolder(unix, "/home/me/project/../other"))
        assertEquals("$unix/a", resolveFolder(unix, "a/b/.."))
    }

    @Test
    fun `a path above the top stays at the top`() {
        assertEquals("/", resolveFolder(unix, "/../.."))
        assertEquals("/home", resolveFolder(unix, "../.."))
    }

    @Test
    fun `a Windows root is written with backslashes, and compared without case`() {
        assertEquals("$windows\\backend\\api", resolveFolder(windows, "backend/api"))
        assertEquals("D:\\data", resolveFolder(windows, "d:/data/"))
        assertEquals("backend", relativeFolder(windows, "c:\\users\\ME\\Project\\backend"))
        assertNull(relativeFolder(windows, "D:\\Users\\me\\project\\backend"))
    }

    @Test
    fun `the folder under the root is told relative, written with a slash`() {
        assertEquals("", relativeFolder(unix, unix))
        assertEquals("", relativeFolder(unix, "$unix/"))
        assertEquals("backend/api", relativeFolder(unix, "$unix/backend/api"))
        assertEquals("backend", relativeFolder("$unix/", "$unix/backend"))
    }

    @Test
    fun `a folder outside the root, or a sibling that starts like it, is not under it`() {
        assertNull(relativeFolder(unix, "/home/me"))
        assertNull(relativeFolder(unix, "/home/me/project-two/src"))
        assertNull(relativeFolder(unix, "/etc"))
    }

    @Test
    fun `a path is absolute when rooted or with a drive`() {
        assertTrue(isAbsolute("/a"))
        assertTrue(isAbsolute("C:\\a"))
        assertTrue(isAbsolute("\\\\server\\share"))
        assertFalse(isAbsolute("a/b"))
        assertFalse(isAbsolute(""))
    }

    @Test
    fun `the parent of a folder, none for a root`() {
        assertEquals("/home/me", parentFolder(unix))
        assertEquals("/", parentFolder("/home"))
        assertNull(parentFolder("/"))
        assertEquals("C:\\Users\\me", parentFolder(windows))
    }

    @Test
    fun `the button says the root, or the folder`() {
        assertEquals("Project root", folderLabel(""))
        assertEquals("backend/api", folderLabel("backend/api"))
    }
}
