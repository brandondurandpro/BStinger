package org.example.packaging

import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.CompilationArtifact
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Compiles a command-line application to a native binary with GraalVM (`native-image`), in `<distDir>/<name>`:
 * no Java runtime to ship or to start, and a fraction of the memory of a JVM. The classes reached by reflection,
 * the resources and the native calls of the application are declared in its
 * `META-INF/native-image/…/reachability-metadata.json`.
 *
 * GraalVM is the one of `GRAALVM_HOME` (`./native-server build` downloads it and sets the variable). The binary is
 * for the system and the processor that built it: there is no cross-compilation.
 */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun nativeImage(
    @Input appJar: CompilationArtifact,
    @Input runtimeClasspath: Classpath,
    @Output distDir: Path,
    name: String,
    mainClass: String,
) {
    val compiler = nativeImageCompiler()
    val jars = (listOf(appJar.artifact) + runtimeClasspath.resolvedFiles).map { it.absolute() }.distinct()
    val output = distDir.absolute().createDirectories().resolve(name)
    val command =
        listOf(compiler.toString(), "-cp", jars.joinToString(File.pathSeparator), mainClass, "-o", output.toString()) +
            NATIVE_IMAGE_OPTIONS
    println("[packaging] ${jars.size} jars, native-image $mainClass")
    run(command)
    val binary = if (Os.current == Os.WINDOWS) output.resolveSibling("$name.exe") else output
    println("[packaging] Done: $binary (${Files.size(binary) / BYTES_PER_MB} MB)")
}

// `native-image` of the GraalVM named by GRAALVM_HOME.
private fun nativeImageCompiler(): Path {
    val home = System.getenv("GRAALVM_HOME")?.takeIf { it.isNotBlank() }
    checkNotNull(home) { "GRAALVM_HOME is not set: run ./native-server build, which downloads GraalVM" }
    val executable = if (Os.current == Os.WINDOWS) "native-image.cmd" else "native-image"
    val compiler = Path.of(home, "bin", executable)
    check(compiler.exists()) { "No native-image in $home: GRAALVM_HOME must name a GraalVM" }
    return compiler
}

// The terminal (pty4j, libghostty-vt) and the return of memory to the system call C functions through the Foreign
// Function & Memory API; `-Ob` halves the build time for a server whose work is input and output. The tools the server
// downloads (ripgrep, language servers) come from https addresses, a protocol a native image only has when told so.
private val NATIVE_IMAGE_OPTIONS =
    listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-H:+UnlockExperimentalVMOptions",
        "-H:+ForeignAPISupport",
        "-H:-UnlockExperimentalVMOptions",
        "--enable-url-protocols=https",
        "-Ob",
    )
