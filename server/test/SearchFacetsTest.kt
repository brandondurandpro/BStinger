package org.example.kotlineditor.server

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.SearchFacets
import org.example.kotlineditor.model.facetsOf
import org.example.kotlineditor.rpc.searchFacets
import org.example.kotlineditor.rpc.toJson
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the filters of Find in Files offer is worked out by the server, from the files of the project. */
class SearchFacetsTest {
    private fun project(vararg files: String) =
        Files.createTempDirectory("facets").also { root ->
            for (file in files) {
                val path = root.resolve(file)
                path.parent.createDirectories()
                path.writeText("x\n")
            }
        }

    @Test
    fun `the server lists every directory at any depth and the most common extensions`() =
        runBlocking {
            val root = project("src/main/A.kt", "src/main/B.kt", "src/test/C.kt", "docs/d.md", "pom.xml", "Makefile")
            val entry = FileEntry(root.toString(), root.fileName.toString(), true)
            val facets = LocalSearch(LocalFileSystem()).facets(entry)
            assertEquals(listOf("docs", "src", "src/main", "src/test"), facets.directories)
            assertEquals(listOf("kt", "md", "xml"), facets.extensions)
        }

    @Test
    fun `usual noise directories the project has are offered as exclusions`() {
        val root = project("dist/a.js", "vendor/x/b.php", "src/c.kt")
        val files = Files.walk(root).filter { Files.isRegularFile(it) }.toList()
        val entries = files.map { FileEntry(it.toString(), it.fileName.toString(), false) }
        assertEquals(listOf("dist/", "vendor/"), facetsOf(entries, root.toString()).excludable)
    }

    @Test
    fun `facets survive the protocol`() {
        val facets = SearchFacets(listOf("src", "src/main"), listOf("kt"), listOf("dist/"))
        assertEquals(facets, facets.toJson().searchFacets())
    }
}
