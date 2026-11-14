package org.example.kotlineditor.syntax

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.e2e.LocalGrammarRepository
import org.example.kotlineditor.e2e.TestGrammars
import org.example.kotlineditor.syntax.treesitter.CaptureStyles
import org.example.kotlineditor.syntax.treesitter.GrammarSpec
import org.example.kotlineditor.syntax.treesitter.InjectedHighlighters
import org.example.kotlineditor.syntax.treesitter.LineSpans
import org.example.kotlineditor.syntax.treesitter.TreeSitterHighlighter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The shell code of a Dockerfile and of a YAML file is colored by the Bash grammar, over the file's own colors. */
class ShellInjectionTest {
    private val repository = LocalGrammarRepository()
    private val store = TestGrammars.store(repository)

    private val dockerfile =
        listOf(
            "FROM golang:1.22 AS builder",
            "ARG VERSION=1.0",
            "RUN apt-get update \\",
            "    # a comment of the instruction",
            "    && rm -rf /var/lib/apt",
            "RUN <<EOF",
            """echo "hi" | cat""",
            "EOF",
            """CMD ["a", "b"]""",
        )

    private val yaml =
        listOf(
            "x-default: &default",
            "  restart: always",
            "services:",
            "  web:",
            "    <<: *default",
            "    command: npm run dev",
            "    ports: [\"8080:80\"]",
            "steps:",
            "  - run: |",
            "      if [ -f x ]; then",
            "        echo \"hi\" | grep h",
            "      fi",
            "  - script:",
            "      - apt-get update",
        )

    @AfterTest
    fun close() = repository.close()

    private fun style(name: String) = CaptureStyles.styleOf(name)

    private fun grammar(id: String) = assertNotNull(runBlocking { store.await(GrammarSpec.all.single { it.id == id }) })

    private fun colored(id: String, lines: List<String>, withBash: Boolean = true): Map<Int, Map<String, Int>> {
        if (withBash) grammar("bash")
        TreeSitterHighlighter(grammar(id)).use { highlighter ->
            InjectedHighlighters(store).use { injected ->
                highlighter.update(lines)
                val own = highlighter.highlight(0, lines.size)
                val spans = runBlocking { injected.overlay(own, lines, highlighter.injections(), 0 until lines.size) }
                return lines.indices.associateWith { styled(lines[it], spans[it]) }
            }
        }
    }

    private fun styled(line: String, spans: LineSpans?): Map<String, Int> {
        val count = spans?.size ?: 0
        return (0 until count).associate { line.substring(spans!!.starts[it], spans.ends[it]) to spans.styles[it] }
    }

    @Test
    fun `the commands of a Dockerfile are colored as shell, continuations and comments included`() {
        val lines = colored("dockerfile", dockerfile)
        assertEquals(style("function"), lines[2]?.get("apt-get"))
        assertEquals(style("function"), lines[4]?.get("rm"))
        assertEquals(style("keyword"), lines[2]?.get("RUN"))
    }

    @Test
    fun `a heredoc of RUN is a script, not a string`() {
        val lines = colored("dockerfile", dockerfile)
        assertEquals(style("function"), lines[6]?.get("echo"))
        assertEquals(style("function"), lines[6]?.get("cat"))
        assertEquals(style("string"), lines[6]?.get("\"hi\""))
        assertNull(lines[6]?.get("echo \"hi\" | cat"), "the base string color under the script is gone")
    }

    @Test
    fun `the exec form stays JSON and the names of the Dockerfile have colors of their own`() {
        val lines = colored("dockerfile", dockerfile)
        assertEquals(style("string"), lines[8]?.get("\"a\""))
        assertEquals(style("label"), lines[0]?.get("builder"))
        assertEquals(style("tag"), lines[0]?.get("golang"))
        assertEquals(style("variable.other.member"), lines[1]?.get("VERSION"))
    }

    @Test
    fun `without the Bash grammar the Dockerfile keeps its own colors, and the grammar is asked for`() {
        val unavailable = repository.unavailable
        unavailable += "bash"
        val lines = colored("dockerfile", dockerfile, withBash = false)
        assertEquals(style("keyword"), lines[2]?.get("RUN"))
        assertEquals(style("label"), lines[0]?.get("builder"))
        assertNull(lines[2]?.get("apt-get"))
    }

    @Test
    fun `the keys of a YAML mapping are not colored like its values`() {
        val lines = colored("yaml", yaml)
        assertEquals(style("variable.other.member"), lines[1]?.get("restart"))
        assertEquals(style("string"), lines[1]?.get("always"))
        assertEquals(style("variable.other.member"), lines[3]?.get("web"))
    }

    @Test
    fun `anchors, aliases and merge keys have a color`() {
        val lines = colored("yaml", yaml)
        assertEquals(style("label"), lines[0]?.get("default"))
        assertEquals(style("label"), lines[4]?.get("default"))
    }

    @Test
    fun `the script of a run block, a command and a script list are colored as shell`() {
        val lines = colored("yaml", yaml)
        assertEquals(style("keyword"), lines[9]?.get("if"))
        assertEquals(style("function"), lines[10]?.get("echo"))
        assertEquals(style("function"), lines[10]?.get("grep"))
        assertEquals(style("function"), lines[5]?.get("npm"))
        assertEquals(style("function"), lines[13]?.get("apt-get"))
        assertNull(lines[8]?.get("run: |"))
    }

    @Test
    fun `a value that is not a script is not colored as shell`() {
        val lines = colored("yaml", yaml)
        assertEquals(style("string"), lines[6]?.get("\"8080:80\""))
    }

    @Test
    fun `Dockerfile variants find the Dockerfile grammar`() {
        for (name in listOf("Dockerfile", "Dockerfile.dev", "Containerfile.prod", "app.dockerfile")) {
            assertEquals("dockerfile", GrammarSpec.forFileName(name)?.id, name)
        }
        assertEquals("yaml", GrammarSpec.forFileName("docker-compose.override.yml")?.id)
        assertEquals("yaml", GrammarSpec.forFileName("compose.yaml")?.id)
        assertNull(GrammarSpec.forFileName("Dockerfiles.txt"))
    }
}
