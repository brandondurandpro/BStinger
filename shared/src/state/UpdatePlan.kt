package org.example.kotlineditor.state

import java.io.File
import java.nio.file.Files

/** The system of this IDE, with the label the release assets use (`kotlin-editor-1.0.0-linux-x64.deb`). */
enum class UpdateOs(val label: String) {
    LINUX("linux"),
    WINDOWS("windows"),
    MAC("macos"),
    ;

    companion object {
        fun fromName(name: String): UpdateOs {
            val lower = name.lowercase()
            return when {
                lower.contains("win") -> WINDOWS
                lower.contains("mac") -> MAC
                else -> LINUX
            }
        }

        /** `x64` or `arm64`, as in the names of the release assets; other architectures keep their name. */
        fun archLabel(arch: String): String =
            when (val lower = arch.lowercase()) {
                "amd64", "x86_64" -> "x64"
                "aarch64", "arm64" -> "arm64"
                else -> lower
            }
    }
}

/** The format of a release asset an install can be replaced with. */
enum class PackageKind(val extension: String) {
    /** One executable file: replaced by the new file. */
    APP_IMAGE(".AppImage"),

    /** The jpackage app image of Linux, `kotlin-editor/` at the top of the archive. */
    TAR_GZ(".tar.gz"),

    /** The jpackage app image of Windows, `Kotlin Editor/` at the top of the archive. */
    ZIP(".zip"),

    /** `Kotlin Editor.app` in a disk image. */
    DMG(".dmg"),
}

/**
 * What the updater knows about how this IDE runs: the launcher of jpackage (`jpackage.app-path`, absent in a
 * development run), the `APPIMAGE` file of an AppImage, the system. A fake one points at a temporary directory in the
 * tests.
 */
data class InstallEnvironment(
    val os: UpdateOs,
    val arch: String,
    val launcher: File?,
    val appImage: File?,
    /**
     * Whether a macOS app replaces itself. Off unless asked with `-Dkotlin.editor.update.macos=true`: that path
     * (hdiutil, ditto, `open -n`) was never run on a Mac, and an update that fails there would leave no app.
     */
    val macInApp: Boolean = false,
) {
    companion object {
        const val LAUNCHER_PROPERTY = "jpackage.app-path"
        const val APPIMAGE_VARIABLE = "APPIMAGE"

        const val APPDIR_VARIABLE = "APPDIR"
        const val MAC_PROPERTY = "kotlin.editor.update.macos"

        fun current(): InstallEnvironment {
            val launcherPath = System.getProperty(LAUNCHER_PROPERTY)?.takeIf { it.isNotBlank() }
            val launcher = launcherPath?.let(::File)
            val appImage = ownAppImage(System.getenv(APPIMAGE_VARIABLE), System.getenv(APPDIR_VARIABLE), launcher)
            return InstallEnvironment(
                UpdateOs.fromName(System.getProperty("os.name")),
                UpdateOs.archLabel(System.getProperty("os.arch")),
                launcher,
                appImage,
                macInApp = System.getProperty(MAC_PROPERTY) == "true",
            )
        }

        /**
         * The AppImage this IDE runs from: the `APPIMAGE` file, only when the [launcher] is inside its mount
         * (`APPDIR`). An IDE started from a program that is itself an AppImage (a terminal, another IDE) inherits
         * that program's variables: its file is not ours to replace.
         */
        fun ownAppImage(appImage: String?, appDir: String?, launcher: File?): File? {
            val image = appImage?.takeIf { it.isNotBlank() }
            val mount = appDir?.takeIf { it.isNotBlank() }
            val insideMount = mount != null && launcher != null && isInside(launcher, File(mount))
            return if (insideMount) image?.let(::File) else null
        }

        private fun isInside(file: File, directory: File): Boolean =
            file.absoluteFile.toPath().startsWith(directory.absoluteFile.toPath())
    }
}

/**
 * The install to replace: what [installed] is (a file for an AppImage, the directory of the app otherwise), and how to
 * start it again. [launcher] is the command line that starts it, once replaced.
 */
data class UpdateTarget(
    val kind: PackageKind,
    val installed: File,
    val launcher: List<String>,
    /** The path inside the install that a complete new version must have: its launcher; empty for an AppImage. */
    val entry: String,
) {
    /** Where the new version is put next to the install, on the same filesystem so that the swap is a rename. */
    val staging: File get() = File(installed.parentFile, installed.name + ".update")

    /** Where the install waits while the new version takes its place. */
    val backup: File get() = File(installed.parentFile, installed.name + ".old")
}

/** How a release can be installed from this IDE. */
sealed interface UpdatePlan {
    /** The IDE replaces itself: [asset] of the release is downloaded, then swapped for [target]. */
    data class InApp(val target: UpdateTarget, val asset: ReleaseAsset) : UpdatePlan

    /** The IDE cannot (or must not) replace itself: the user is sent to the page of the release, for [reason]. */
    data class Manual(val reason: String) : UpdatePlan
}

/**
 * Chooses how to install a release, from what the IDE finds out about its own install at run time, not from the
 * format it was delivered in. Never escalates privileges: an install the user cannot write is a manual one.
 */
object UpdatePlanner {
    /** The name of the asset of [version] for [kind] on this system: `kotlin-editor-1.0.0-linux-x64.tar.gz`. */
    fun assetName(version: AppVersion, os: UpdateOs, arch: String, kind: PackageKind): String =
        "kotlin-editor-$version-${os.label}-$arch${kind.extension}"

    fun plan(environment: InstallEnvironment, release: AvailableRelease): UpdatePlan =
        when (val located = InstallLocator.locate(environment)) {
            is Located.Refused -> UpdatePlan.Manual(located.reason)
            is Located.Found -> planAsset(environment, release, located.target)
        }

    // The release must carry the file for this system, in the format of this install.
    private fun planAsset(
        environment: InstallEnvironment,
        release: AvailableRelease,
        target: UpdateTarget,
    ): UpdatePlan {
        val name = assetName(release.version, environment.os, environment.arch, target.kind)
        val asset = release.assets.firstOrNull { it.name == name }
        val missing = "This release has no download for ${environment.os.label} ${environment.arch}."
        return if (asset == null) UpdatePlan.Manual(missing) else UpdatePlan.InApp(target, asset)
    }
}

internal sealed interface Located {
    data class Found(val target: UpdateTarget) : Located

    data class Refused(val reason: String) : Located
}

/** Finds the install the IDE runs from, and tells whether it can be replaced. */
internal object InstallLocator {
    private val PACKAGE_MANAGED_ROOTS = listOf("/opt/", "/usr/")
    private const val NOT_INSTALLED = "This IDE is not an installed one (a development run)."
    private const val UNKNOWN_LAYOUT = "The layout of this install is unknown."
    private const val PACKAGE_MANAGED = "This IDE is installed by your package manager: update it with the package."
    private const val MAC_NOT_YET = "On macOS the IDE does not replace itself yet: install the new version by hand."
    private const val TRANSLOCATED = "macOS runs this app from a temporary copy: move it to Applications first."

    fun locate(environment: InstallEnvironment): Located {
        val appImage = environment.appImage
        val launcher = environment.launcher
        return when {
            environment.os == UpdateOs.LINUX && appImage != null -> locateAppImage(appImage)
            environment.os == UpdateOs.MAC && !environment.macInApp -> Located.Refused(MAC_NOT_YET)
            launcher == null -> Located.Refused(NOT_INSTALLED)
            else -> locateDirectory(environment.os, launcher)
        }
    }

    private fun locateAppImage(file: File): Located {
        val directory = file.absoluteFile.parentFile
        val problem = appImageProblem(file, directory)
        val target = UpdateTarget(PackageKind.APP_IMAGE, file.absoluteFile, listOf(file.absolutePath), entry = "")
        return if (problem == null) Located.Found(target) else Located.Refused(problem)
    }

    private fun appImageProblem(file: File, directory: File): String? {
        val writable = Files.isWritable(file.toPath()) && Files.isWritable(directory.toPath())
        return when {
            !file.isFile -> "The AppImage file ${file.name} cannot be found."
            !writable -> "The AppImage is in a folder you cannot write: $directory."
            else -> null
        }
    }

    private fun locateDirectory(os: UpdateOs, launcher: File): Located {
        val root = InstallLayout.rootOf(os, launcher.absoluteFile)
        val problem = directoryProblem(os, launcher, root)
        return when {
            problem != null -> {
                Located.Refused(problem)
            }

            root == null -> {
                Located.Refused(UNKNOWN_LAYOUT)
            }

            else -> {
                Located.Found(
                    UpdateTarget(
                        InstallLayout.kindOf(os),
                        root,
                        InstallLayout.launchCommand(os, root, launcher),
                        InstallLayout.entryOf(os, root, launcher),
                    ),
                )
            }
        }
    }

    // What makes an install one the IDE must not replace: managed by a package, translocated, or not writable.
    private fun directoryProblem(os: UpdateOs, launcher: File, root: File?): String? {
        val path = launcher.path.replace('\\', '/')
        val managed = os == UpdateOs.LINUX && PACKAGE_MANAGED_ROOTS.any { path.startsWith(it) }
        return when {
            managed -> PACKAGE_MANAGED
            path.contains("/AppTranslocation/") -> TRANSLOCATED
            root != null && !isWritable(root) -> "This IDE is installed in a folder you cannot write: $root."
            else -> null
        }
    }

    // Swapping renames the install, in its parent folder.
    private fun isWritable(root: File): Boolean {
        val parent = root.parentFile ?: return false
        return Files.isWritable(root.toPath()) && Files.isWritable(parent.toPath())
    }
}

/** The layout of the app image of each system: where its root is, how it is started, what a complete one has. */
internal object InstallLayout {
    private const val LINUX_LAUNCHER_DEPTH = 2
    private const val MAC_BUNDLE_DEPTH = 3
    private const val MAC_ENTRY = "Contents/MacOS"

    // The directory that holds everything: the launcher is in `<root>/bin`, `<root>`, or `<root>/Contents/MacOS`.
    fun rootOf(os: UpdateOs, launcher: File): File? {
        val root =
            when (os) {
                UpdateOs.LINUX -> ancestor(launcher, LINUX_LAUNCHER_DEPTH)
                UpdateOs.WINDOWS -> launcher.parentFile
                UpdateOs.MAC -> ancestor(launcher, MAC_BUNDLE_DEPTH)
            }
        val known = root != null && launcher.isFile
        return if (known) root?.takeIf { hasApplication(os, it) } else null
    }

    private fun ancestor(file: File, levels: Int): File? {
        var current: File? = file
        repeat(levels) { current = current?.parentFile }
        return current
    }

    private fun hasApplication(os: UpdateOs, root: File): Boolean =
        when (os) {
            UpdateOs.LINUX -> File(root, "lib/app").isDirectory
            UpdateOs.WINDOWS -> File(root, "app").isDirectory
            UpdateOs.MAC -> root.name.endsWith(".app") && File(root, MAC_ENTRY).isDirectory
        }

    fun kindOf(os: UpdateOs): PackageKind =
        when (os) {
            UpdateOs.LINUX -> PackageKind.TAR_GZ
            UpdateOs.WINDOWS -> PackageKind.ZIP
            UpdateOs.MAC -> PackageKind.DMG
        }

    // The app is started again by its launcher; a macOS bundle by `open -n`, as Finder would.
    fun launchCommand(os: UpdateOs, root: File, launcher: File): List<String> =
        if (os == UpdateOs.MAC) listOf("open", "-n", root.path) else listOf(launcher.path)

    fun entryOf(os: UpdateOs, root: File, launcher: File): String =
        if (os == UpdateOs.MAC) MAC_ENTRY else root.toPath().relativize(launcher.toPath()).toString()
}
