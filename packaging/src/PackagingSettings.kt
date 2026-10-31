package org.example.packaging

import org.jetbrains.amper.plugins.Configurable

// One property per option of the plugin.yaml schema: splitting it would only spread the settings around.

/** Settings of the plugin, under `plugins: packaging:` in the module.yaml of an application. */
@Suppress("ComplexInterface")
@Configurable
interface PackagingSettings {
    /** File name of the launcher, of the Linux package and of the deliverables (`kotlin-editor`). */
    val name: String

    /** Name shown to the user: application menu, macOS and Windows application. */
    val displayName: String

    /** Fully qualified name of the main class. */
    val mainClass: String

    /** One line describing the application, in the installers and the application menu. */
    val description: String get() = ""

    /** Publisher of the application, in the installers. */
    val vendor: String get() = ""

    /** Version of the package, replaced by the `APP_VERSION` environment variable when set (CI). */
    val version: String get() = "1.0.0"

    /** Java modules of the runtime; empty for the default set of jpackage (every module). */
    val modules: List<String> get() = emptyList()

    /** Command-line application: a console launcher on Windows, no menu entry nor installer. */
    val console: Boolean get() = false

    /** Builds the installer of the system: .deb (and .rpm) on Linux, .dmg on macOS; none on Windows. */
    val installer: Boolean get() = true

    /** Also builds an AppImage on Linux, from the runtime file given by `APPIMAGE_RUNTIME`. */
    val appImage: Boolean get() = false

    /** Generates an AOT cache (Project Leyden, JDK 24+) for a faster startup; Linux only, needs a display. */
    val aot: Boolean get() = false

    /** Maximum duration of the AOT training run, after which the application is stopped. */
    val aotTrainingSeconds: Int get() = 30

    /** Compresses the runtime: smaller once installed, but a bigger download. */
    val compress: Boolean get() = false
}
