package org.example.kotlineditor.plugin

import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals

private const val PACKAGE = "org/example/kotlineditor/plugin"
private const val DUMP = "plugin-api/api/plugin-api.api"
private const val UPDATE = "UPDATE_API_DUMP"

/**
 * The public API of the `plugin` package, compared with the dump checked in at `plugin-api/api`: a change of it is a
 * change of the contract with the plugins, made on purpose. Run with `UPDATE_API_DUMP=1` to write the new dump.
 */
class ApiDumpTest {
    @Test
    fun `the public API is the one in the checked-in dump`() {
        val actual = apiLines().joinToString("\n", postfix = "\n")
        val file = locateDump()
        if (System.getenv(UPDATE) != null) file.writeText(actual)
        assertEquals(file.readText(), actual, "plugin-api changed: review it, then run with $UPDATE=1")
    }

    private fun locateDump(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null && !File(directory, DUMP).parentFile.isDirectory) directory = directory.parentFile
        return File(checkNotNull(directory) { "No $DUMP above ${File("").absolutePath}" }, DUMP)
    }

    private fun apiLines(): List<String> = classNames().sorted().flatMap { describe(Class.forName(it)) }

    private fun classNames(): List<String> {
        val location = File(ToolWindowRegistry::class.java.protectionDomain.codeSource.location.toURI())
        val entries =
            if (location.isDirectory) {
                location.walkTopDown().filter { it.isFile }.map { it.relativeTo(location).path.replace('\\', '/') }
            } else {
                JarFile(location).use { jar -> jar.entries().asSequence().map { it.name }.toList().asSequence() }
            }
        return entries
            .filter { it.startsWith("$PACKAGE/") && it.endsWith(".class") && '$' !in it }
            .map { it.removeSuffix(".class").replace('/', '.') }
            .filterNot { it.endsWith("Kt") }
            .toList()
    }

    private fun describe(type: Class<*>): List<String> {
        if (!Modifier.isPublic(type.modifiers)) return emptyList()
        val header = "class ${type.name}"
        val methods =
            type.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .map { method ->
                    val parameters = method.parameterTypes.joinToString(",") { it.simpleName }
                    "  ${method.name}($parameters): ${method.returnType.simpleName}"
                }
        val constructors =
            type.declaredConstructors
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .map { constructor -> "  <init>(${constructor.parameterTypes.joinToString(",") { it.simpleName }})" }
        return listOf(header) + (constructors + methods).sorted()
    }
}
