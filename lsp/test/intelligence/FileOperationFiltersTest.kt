package org.example.kotlineditor.lsp.intelligence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.example.kotlineditor.model.FileOperation
import org.example.kotlineditor.model.OperatedFile
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val WILL_RENAME = "willRename"

class FileOperationFiltersTest {
    private val phpFiles = workspace("""{"scheme": "file", "pattern": {"glob": "**/*.php", "matches": "file"}}""")

    private fun workspace(filter: String): JsonElement =
        Json.parseToJsonElement("""{"fileOperations": {"willRename": {"filters": [$filter]}}}""")

    @Test
    fun `a glob of every PHP file matches PHP files in any directory, and nothing else`() {
        assertTrue(fileOperationMatches(phpFiles, WILL_RENAME, "/home/me/project/src/Greeter.php", false))
        assertFalse(fileOperationMatches(phpFiles, WILL_RENAME, "/home/me/project/notes.txt", false))
        assertTrue(fileOperationMatches(phpFiles, WILL_RENAME, """C:\project\src\Greeter.php""", false))
    }

    @Test
    fun `the kind of the filter tells files from folders`() {
        assertFalse(fileOperationMatches(phpFiles, WILL_RENAME, "/project/lib.php", true))
        val folders = workspace("""{"pattern": {"glob": "**/src", "matches": "folder"}}""")
        assertTrue(fileOperationMatches(folders, WILL_RENAME, "/project/src", true))
        assertFalse(fileOperationMatches(folders, WILL_RENAME, "/project/src", false))
        val both = workspace("""{"pattern": {"glob": "**/src"}}""")
        assertTrue(fileOperationMatches(both, WILL_RENAME, "/project/src", false))
        assertTrue(fileOperationMatches(both, WILL_RENAME, "/project/src", true))
    }

    @Test
    fun `case is ignored only when the filter says so`() {
        assertFalse(fileOperationMatches(phpFiles, WILL_RENAME, "/project/Greeter.PHP", false))
        val ignoring = workspace("""{"pattern": {"glob": "**/*.php", "options": {"ignoreCase": true}}}""")
        assertTrue(fileOperationMatches(ignoring, WILL_RENAME, "/project/Greeter.PHP", false))
    }

    @Test
    fun `another scheme, another operation, a broken glob or no capability match nothing`() {
        val untitled = workspace("""{"scheme": "untitled", "pattern": {"glob": "**/*.php"}}""")
        assertFalse(fileOperationMatches(untitled, WILL_RENAME, "/project/a.php", false))
        assertFalse(fileOperationMatches(phpFiles, "didRename", "/project/a.php", false))
        val broken = workspace("""{"pattern": {"glob": "[a"}}""")
        assertFalse(fileOperationMatches(broken, WILL_RENAME, "/project/a.php", false))
        assertFalse(fileOperationMatches(null, WILL_RENAME, "/project/a.php", false))
        assertFalse(fileOperationMatches(workspace("{}"), WILL_RENAME, "/project/a.php", false))
    }

    @Test
    fun `the params name the old and new URIs of a rename, the URI otherwise`() {
        val old = Paths.get("/project/A.php")
        val new = Paths.get("/project/B.php")
        val renamed = fileOperationParams(FileOperation.Rename, listOf(OperatedFile(old.toString(), new.toString())))
        val expected = """{"files":[{"oldUri":"${old.toUri()}","newUri":"${new.toUri()}"}]}"""
        assertEquals(expected, renamed.toString())
        val deleted = fileOperationParams(FileOperation.Delete, listOf(OperatedFile(old.toString())))
        assertEquals("""{"files":[{"uri":"${old.toUri()}"}]}""", deleted.toString())
    }

    @Test
    fun `only the files a server asks about are sent to it`() {
        val files = listOf(OperatedFile("/p/A.php", "/p/B.php"), OperatedFile("/p/a.txt", "/p/b.txt"))
        assertEquals(listOf(files[0]), filesMatching(phpFiles, WILL_RENAME, files))
    }

    @Test
    fun `globs match like LSP's, whatever the system`() {
        val cases =
            listOf(
                Triple("**/*.{php,phtml}", "/p/views/a.phtml", true),
                Triple("**/*.{php,phtml}", "/p/views/a.html", false),
                Triple("src/*.php", "src/a.php", true),
                Triple("src/*.php", "src/sub/a.php", false),
                Triple("src/**", "src/sub/a.php", true),
                Triple("**/a?.php", "/p/ab.php", true),
                Triple("**/[a-c].php", "/p/b.php", true),
                Triple("**/[!a-c].php", "/p/b.php", false),
                Triple("**/a+b.php", "/p/a+b.php", true),
            )
        for ((glob, path, expected) in cases) {
            assertEquals(expected, Regex(globRegex(glob)).matches(path), "$glob on $path")
        }
    }
}
