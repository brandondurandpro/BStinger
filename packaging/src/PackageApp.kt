package org.example.packaging

import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.CompilationArtifact
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolute
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Packages the application with its own Java runtime (jlink + jpackage), then the deliverables of the system,
 * named `<name>-<version>-<os>-<arch>.<ext>` in `<distDir>/deliverables`:
 * - Linux: `.tar.gz` of the application, `.deb` (and `.rpm` when rpmbuild is installed), `.AppImage`;
 * - Windows: `.zip` of the application;
 * - macOS: `.dmg`, or `.tar.gz` of the application without installer.
 *
 * The Kotlin Toolchain (0.13) only makes jars, and its executable jar loses the dependencies whose file names
 * collide: this task takes the jars from the resolved classpath instead.
 *
 * The CI sets the version with `APP_VERSION` (numbers only: jpackage refuses anything else) and the one in file
 * names with `APP_VERSION_LABEL` (`1.2.3-rc1`).
 */
@Suppress("LongParameterList") // the parameters are the settings, given one by one by plugin.yaml
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun packageApp(
    @Input appJar: CompilationArtifact,
    @Input runtimeClasspath: Classpath,
    @Input iconsDir: Path,
    @Output distDir: Path,
    name: String,
    displayName: String,
    mainClass: String,
    description: String,
    vendor: String,
    version: String,
    modules: List<String>,
    console: Boolean,
    installer: Boolean,
    appImage: Boolean,
    aot: Boolean,
    aotTrainingSeconds: Int,
    compress: Boolean,
) {
    val app =
        App(
            name = name,
            displayName = displayName,
            description = description,
            vendor = vendor,
            version = System.getenv("APP_VERSION")?.takeIf { it.isNotBlank() } ?: version,
            versionLabel = System.getenv("APP_VERSION_LABEL")?.takeIf { it.isNotBlank() } ?: version,
            console = console,
            dist = distDir.absolute(),
            icon = iconsDir.resolve(Os.current.icon),
        )
    @OptIn(ExperimentalPathApi::class)
    if (app.dist.exists()) app.dist.deleteRecursively()
    val jars = (listOf(appJar.artifact) + runtimeClasspath.resolvedFiles).map { it.absolute() }.distinct()
    val jdk = Jdk.find()
    val image = buildAppImageDirectory(jdk, app, jars, mainClass, modules, compress)

    if (Os.current == Os.LINUX) stripNativeLibraries(image.resolve("lib/runtime"))
    if (aot) addAotCache(app, image, aotTrainingSeconds)
    archive(app, image, withInstaller = installer)
    if (Os.current == Os.LINUX && appImage) buildAppImage(app, image)
    if (installer && !console && Os.current != Os.WINDOWS) {
        buildInstaller(jdk, app, image)
        app.dist
            .listDirectoryEntries()
            .filter { it.isRegularFile() && it.name.substringAfterLast('.') in INSTALLERS }
            .forEach { Files.move(it, app.deliverable(it.name.substringAfterLast('.'))) }
    }
    println("[packaging] Done:")
    app.deliverables.listDirectoryEntries().sorted().forEach {
        println("[packaging]   $it (${Files.size(it) / BYTES_PER_MB} MB)")
    }
}

private val INSTALLERS = setOf("deb", "rpm", "dmg")

internal const val BYTES_PER_MB = 1_000_000

/** What the steps of the packaging share. */
internal class App(
    /** File name of the launcher, of the Linux package and of the deliverables. */
    val name: String,
    /** Name shown to the user. */
    val displayName: String,
    /** One line describing the application. */
    val description: String,
    /** Publisher of the application. */
    val vendor: String,
    /** Numeric version, for jpackage. */
    val version: String,
    /** Version in the names of the deliverables. */
    val versionLabel: String,
    /** Command-line application. */
    val console: Boolean,
    /** Output directory of the task. */
    val dist: Path,
    /** Icon in the format of the current system; may not exist. */
    val icon: Path,
) {
    /** Name of the launcher and of the application directory: a Linux launcher keeps the file name. */
    val appName = if (Os.current == Os.LINUX || console) name else displayName

    /** Directory of the files to publish. */
    val deliverables: Path get() = dist.resolve("deliverables").createDirectories()

    /** Path of the deliverable with the extension [extension]. */
    fun deliverable(extension: String): Path =
        deliverables.resolve("$name-$versionLabel-${Os.current.label}-$arch.$extension")
}

// jpackage app-image: the application with its runtime, from the jars copied with a numbered name, since names
// repeat and the classpath order matters.
@Suppress("LongParameterList")
private fun buildAppImageDirectory(
    jdk: Jdk,
    app: App,
    jars: List<Path>,
    mainClass: String,
    modules: List<String>,
    compress: Boolean,
): Path {
    val input = app.dist.resolve("input").createDirectories()
    val files = jars.filter { !it.isDirectory() }
    files.forEachIndexed { i, jar -> jar.copyTo(input.resolve("%03d-%s".format(i, jar.name))) }
    println("[packaging] ${files.size} jars")

    val jlinkOptions =
        listOf("--strip-native-commands", "--strip-debug", "--no-man-pages", "--no-header-files") +
            if (compress) listOf("--compress", "zip-6") else emptyList()
    val options =
        buildList {
            addAll(listOf("--type", "app-image", "--dest", app.dist, "--name", app.appName))
            addAll(listOf("--app-version", app.version, "--vendor", app.vendor, "--description", app.description))
            addAll(listOf("--input", input, "--main-jar", "000-${files.first().name}", "--main-class", mainClass))
            addAll(listOf("--jlink-options", jlinkOptions.joinToString(" ")))
            // libghostty-vt and pty4j use the Foreign Function & Memory API.
            addAll(listOf("--java-options", "--enable-native-access=ALL-UNNAMED"))
            if (Files.exists(app.icon)) addAll(listOf("--icon", app.icon))
            if (modules.isNotEmpty()) addAll(listOf("--add-modules", modules.joinToString(",")))
            if (app.console && Os.current == Os.WINDOWS) add("--win-console")
            if (Os.current == Os.MAC) addAll(listOf("--mac-package-identifier", "org.example.${app.name}"))
        }
    println("[packaging] Application with its runtime (jpackage app-image)")
    jdk.run("jpackage", options)
    return app.dist.resolve(if (Os.current == Os.MAC) "${app.appName}.app" else app.appName)
}

// The application as an archive: the only deliverable on Windows (an installer would need WiX), next to the
// installer on Linux, and on macOS only without installer.
private fun archive(app: App, image: Path, withInstaller: Boolean) {
    when (Os.current) {
        Os.WINDOWS -> zip(image, app.deliverable("zip"))
        Os.LINUX -> tarGz(image, app.deliverable("tar.gz"))
        Os.MAC -> if (app.console || !withInstaller) tarGz(image, app.deliverable("tar.gz"))
    }
}
