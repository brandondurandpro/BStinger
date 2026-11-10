package org.example.kotlineditor.testkit

import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

private const val CLASS_NAME = "fixture.TodoPlugin"

/**
 * A plugin folder built at test time: Java source compiled against the classpath of the test, packed in `client.jar`
 * with a manifest. [body] is the body of `register(ClientContributions c)`.
 */
object PluginFixture {
    fun create(
        parent: File,
        id: String,
        apiVersion: Int,
        body: String = "",
        extraManifest: String = "",
    ): File {
        val folder = File(parent, id).apply { mkdirs() }
        File(folder, "plugin.yaml")
            .writeText("id: $id\nversion: 1.0\napiVersion: $apiVersion\nclient: $CLASS_NAME\n$extraManifest")
        compileInto(File(folder, "client.jar"), source(body))
        return folder
    }

    private fun source(body: String) =
        """
        package fixture;
        public class TodoPlugin implements org.example.kotlineditor.plugin.ClientPlugin {
            public void register(org.example.kotlineditor.plugin.ClientContributions c) { $body }
        }
        """.trimIndent()

    private fun compileInto(jar: File, source: String) {
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "These tests need a JDK" }
        val work = Files.createTempDirectory("plugin-fixture").toFile()
        val file = File(work, "fixture/TodoPlugin.java").apply { parentFile.mkdirs() }
        file.writeText(source)
        val classpath = System.getProperty("java.class.path")
        val status = compiler.run(null, null, null, "-cp", classpath, "-d", work.path, file.path)
        check(status == 0) { "The fixture does not compile" }
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("fixture/TodoPlugin.class"))
            out.write(File(work, "fixture/TodoPlugin.class").readBytes())
            out.closeEntry()
        }
        work.deleteRecursively()
    }
}
