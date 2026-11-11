package org.example.kotlineditor.testkit

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider

private const val CLIENT_CLASS = "fixture.TodoPlugin"
private const val SERVER_CLASS = "fixture.TodoServerPlugin"

/** The server half of a fixture plugin: the manifest to send and the bytes of `server.jar`. */
class ServerHalf(val manifest: String, val jar: ByteArray)

/**
 * Plugins built at test time: Java source compiled against the classpath of the test and packed in a jar with a
 * manifest, as a third party's would be.
 */
object PluginFixture {
    /** A folder `<id>/` with a client half; [body] is the body of `register(ClientContributions c)`. */
    fun create(
        parent: File,
        id: String,
        apiVersion: Int,
        body: String = "",
        extraManifest: String = "",
    ): File {
        val folder = File(parent, id).apply { mkdirs() }
        File(folder, "plugin.yaml")
            .writeText("id: $id\nversion: 1.0\napiVersion: $apiVersion\nclient: $CLIENT_CLASS\n$extraManifest")
        File(folder, "client.jar").writeBytes(jarOf("fixture/TodoPlugin", clientSource(body)))
        return folder
    }

    /** A folder `<id>/` with a client half and a server half; [serverBody] is the body of the server `register`. */
    fun createWithServer(parent: File, id: String, apiVersion: Int, clientBody: String, serverBody: String): File {
        val folder = create(parent, id, apiVersion, clientBody, extraManifest = "server: $SERVER_CLASS\n")
        File(folder, "server.jar").writeBytes(serverHalf(id, serverBody).jar)
        return folder
    }

    /** The server half of plugin [id]; [body] is the body of `register(ServerContributions c)`. */
    fun serverHalf(id: String, body: String, version: String = "1.0", apiVersion: Int = 1): ServerHalf {
        val manifest = "id: $id\nversion: $version\napiVersion: $apiVersion\nserver: $SERVER_CLASS\n"
        return ServerHalf(manifest, jarOf("fixture/TodoServerPlugin", serverSource(body)))
    }

    /** Packs the files of plugin folder [folder] into the archive [target], as `<id>.plugin` is distributed. */
    fun archive(folder: File, target: File): File {
        ZipOutputStream(target.outputStream()).use { zip ->
            for (file in folder.listFiles { entry -> entry.isFile }.orEmpty()) {
                zip.putNextEntry(ZipEntry(file.name))
                zip.write(file.readBytes())
                zip.closeEntry()
            }
        }
        return target
    }

    private fun clientSource(body: String) =
        """
        package fixture;
        public class TodoPlugin implements org.example.kotlineditor.plugin.ClientPlugin {
            public void register(org.example.kotlineditor.plugin.ClientContributions c) { $body }
        }
        """.trimIndent()

    private fun serverSource(body: String) =
        """
        package fixture;
        public class TodoServerPlugin implements org.example.kotlineditor.plugin.server.ServerPlugin {
            public void register(org.example.kotlineditor.plugin.server.ServerContributions c) { $body }
        }
        """.trimIndent()

    private fun jarOf(path: String, source: String): ByteArray {
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "These tests need a JDK" }
        val work = Files.createTempDirectory("plugin-fixture").toFile()
        val file = File(work, "$path.java").apply { parentFile.mkdirs() }
        file.writeText(source)
        val classpath = System.getProperty("java.class.path")
        val errors = ByteArrayOutputStream()
        val status = compiler.run(null, null, errors, "-cp", classpath, "-d", work.path, file.path)
        check(status == 0) { "The fixture does not compile: $errors" }
        val bytes = ByteArrayOutputStream()
        JarOutputStream(bytes).use { out ->
            out.putNextEntry(JarEntry("$path.class"))
            out.write(File(work, "$path.class").readBytes())
            out.closeEntry()
        }
        work.deleteRecursively()
        return bytes.toByteArray()
    }
}
