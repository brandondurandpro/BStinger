package org.example.packaging

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

// Dependencies of the .deb. jpackage infers them from the packages of the build machine, and misses the libraries
// loaded at run time (fontconfig and OpenGL by Skiko).
private val debDependencies =
    listOf(
        "libasound2t64 | libasound2",
        "libc6",
        "libx11-6",
        "libxext6",
        "libxi6",
        "libxrender1",
        "libxtst6",
        "libfontconfig1",
        "libgl1",
        "xdg-utils",
    )

// Dependencies of the .rpm, by library rather than by package: package names differ between Fedora, openSUSE and
// RHEL, and jpackage only infers them from the rpm database of the build machine (empty on Ubuntu).
private val rpmDependencies =
    listOf(
        "libasound.so.2()(64bit)",
        "libX11.so.6()(64bit)",
        "libXext.so.6()(64bit)",
        "libXi.so.6()(64bit)",
        "libXrender.so.1()(64bit)",
        "libXtst.so.6()(64bit)",
        "libfontconfig.so.1()(64bit)",
        "libGL.so.1()(64bit)",
        "xdg-utils",
    )

private val executable =
    PosixFilePermission.entries.toSet() -
        setOf(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)

/** Builds the installer of the system from the application [image]: .deb (and .rpm) on Linux, .dmg on macOS. */
internal fun buildInstaller(jdk: Jdk, app: App, image: Path) {
    println("[packaging] Installer")
    // Built from an app image, jpackage ignores --icon and puts the default Java icon in the installer (application
    // menu): it is replaced by a resource file.
    val resources = app.dist.resolve("resources").createDirectories()
    if (app.icon.exists()) {
        app.icon.copyTo(
            resources.resolve("${app.appName}.${Os.current.icon.substringAfterLast('.')}"),
        )
    }
    val common =
        listOf(
            "--dest", app.dist, "--app-image", image, "--name", app.appName, "--app-version", app.version,
            "--vendor", app.vendor, "--description", app.description, "--resource-dir", resources,
        )
    when (Os.current) {
        Os.LINUX -> buildLinuxPackages(jdk, app, resources, common)
        Os.MAC -> jdk.jpackage("dmg", common)
        Os.WINDOWS -> Unit
    }
}

private fun buildLinuxPackages(jdk: Jdk, app: App, resources: Path, common: List<Any>) {
    writeLinuxTemplates(jdk, resources, app.name)
    val linux =
        common +
            listOf(
                "--linux-package-name",
                app.name,
                "--linux-shortcut",
                "--linux-menu-group",
                "Development",
                "--linux-app-category",
                "devel",
            )
    jdk.jpackage("deb", linux)
    // .rpm (Fedora, openSUSE, RHEL): needs rpmbuild (the `rpm` package on Debian / Ubuntu).
    if (isOnPath("rpmbuild")) {
        jdk.jpackage("rpm", linux + listOf("--linux-package-deps", rpmDependencies.joinToString(", ")))
    } else {
        println("[packaging] No .rpm: rpmbuild not found")
    }
}

// Templates of jpackage (`--resource-dir` files, whose upper-case words jpackage replaces), read from the JDK and
// patched: explicit dependencies of the .deb; menu directories created before `xdg-desktop-menu` in the install
// scripts (missing from a minimal system, which makes dpkg fail); jars given back the fixed date that the AOT cache
// expects; and the .rpm copying the application with its dates (`cp -a`).
private fun writeLinuxTemplates(jdk: Jdk, resources: Path, linuxPackage: String) {
    fun patch(template: String, old: Regex, new: String): String =
        template.replaceFirst(old, new).also { check(it != template) { "Unexpected jpackage template: no $old" } }
    val preamble =
        "mkdir -p /usr/share/applications /usr/share/desktop-directories\n" +
            "touch -m -d @$JAR_EPOCH_SECONDS /opt/$linuxPackage/lib/app/*.jar"
    val desktopInstall = Regex("^DESKTOP_COMMANDS_INSTALL$", RegexOption.MULTILINE)
    val dependencies = Regex("^Depends: .*$", RegexOption.MULTILINE)
    resources
        .resolve("control")
        .writeText(
            patch(jdk.jpackageTemplate("control"), dependencies, "Depends: ${debDependencies.joinToString(", ")}"),
        )
    resources
        .resolve("postinst")
        .writeText(patch(jdk.jpackageTemplate("postinst"), desktopInstall, "$preamble\nDESKTOP_COMMANDS_INSTALL"))
    val spec = patch(jdk.jpackageTemplate("spec"), desktopInstall, "$preamble\nDESKTOP_COMMANDS_INSTALL")
    val copy = Regex("^cp -r (%\\{_sourcedir}APPLICATION_DIRECTORY)", RegexOption.MULTILINE)
    resources.resolve("$linuxPackage.spec").writeText(patch(spec, copy, "cp -a $1"))
}

/**
 * Builds the AppImage of the application [image]: the type 2 runtime (`APPIMAGE_RUNTIME`,
 * https://github.com/AppImage/type2-runtime) followed by a SquashFS image of the AppDir (mksquashfs, from
 * squashfs-tools), which is all appimagetool does. The runtime mounts it with FUSE and starts `AppRun`.
 */
internal fun buildAppImage(app: App, image: Path) {
    val runtime = System.getenv("APPIMAGE_RUNTIME")?.let { Path.of(it) }
    val hasRuntime = runtime != null && runtime.exists()
    if (!hasRuntime || !isOnPath("mksquashfs")) {
        println("[packaging] No AppImage: needs APPIMAGE_RUNTIME (type 2 runtime) and mksquashfs")
        return
    }
    check(app.icon.exists()) { "An AppImage needs an icon: ${app.icon}" }
    println("[packaging] AppImage")
    val appDir = app.dist.resolve("AppDir")
    // cp -a keeps the dates of the jars, which the AOT cache checks.
    run(listOf("cp", "-a", image.toString(), appDir.toString()))
    appDir.resolve("AppRun").apply {
        writeText(
            """
            #!/bin/sh
            HERE="${'$'}(dirname "${'$'}(readlink -f "${'$'}0")")"
            exec "${'$'}HERE/bin/${app.name}" "${'$'}@"
            """.trimIndent() + "\n",
        )
        setPosixFilePermissions(executable)
    }
    appDir.resolve("${app.name}.desktop").writeText(
        """
        [Desktop Entry]
        Type=Application
        Name=${app.displayName}
        Comment=${app.description}
        Exec=${app.name} %F
        Icon=${app.name}
        Categories=Development;IDE;
        Terminal=false
        """.trimIndent() + "\n",
    )
    app.icon.copyTo(appDir.resolve("${app.name}.png"))
    Files.createSymbolicLink(appDir.resolve(".DirIcon"), Path.of("${app.name}.png"))

    val squashfs = app.dist.resolve("AppDir.squashfs")
    run(
        listOf(
            "mksquashfs",
            appDir.toString(),
            squashfs.toString(),
            "-root-owned",
            "-noappend",
            "-comp",
            "zstd",
            "-quiet",
        ),
    )
    val output = app.deliverable("AppImage")
    Files.newOutputStream(output).use { out ->
        Files.copy(runtime, out)
        Files.copy(squashfs, out)
    }
    output.setPosixFilePermissions(executable)
}

/** Removes the symbols of the native libraries of the [runtime] (JVM…): no effect on how they work. */
internal fun stripNativeLibraries(runtime: Path) {
    val strip = File("/usr/bin/strip")
    if (!strip.canExecute() || !runtime.isDirectory()) return
    runtime
        .toFile()
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith(".so") }
        // A library strip cannot read stays as it is.
        .forEach { runCatching { run(listOf(strip.path, "--strip-unneeded", it.path)) } }
}

private fun Jdk.jpackage(type: String, options: List<Any>) = run("jpackage", listOf("--type", type) + options)
