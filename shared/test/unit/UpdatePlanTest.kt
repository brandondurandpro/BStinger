package org.example.kotlineditor.unit

import org.example.kotlineditor.state.AppVersion
import org.example.kotlineditor.state.AvailableRelease
import org.example.kotlineditor.state.InstallEnvironment
import org.example.kotlineditor.state.PackageKind
import org.example.kotlineditor.state.ReleaseAsset
import org.example.kotlineditor.state.UpdateOs
import org.example.kotlineditor.state.UpdatePlan
import org.example.kotlineditor.state.UpdatePlanner
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * How a release is installed, decided from the install found at run time. The systems are data (the environment names
 * one), so every layout is checked on every machine, in temporary directories.
 */
class UpdatePlanTest {
    private val base: File = Files.createTempDirectory("update-plan").toFile()

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun release(vararg names: String, tag: String = "v0.3.0"): AvailableRelease {
        val version = checkNotNull(AppVersion.parse(tag))
        return AvailableRelease(tag, version, names.map { ReleaseAsset(it, SIZE) })
    }

    private fun file(path: String): File {
        val file = File(base, path)
        file.parentFile.mkdirs()
        file.writeText("x")
        return file
    }

    private fun linuxInstall(root: String = "tree/kotlin-editor"): File {
        File(base, "$root/lib/app").mkdirs()
        return file("$root/bin/kotlin-editor")
    }

    // The macOS path is asked for here, as `-Dkotlin.editor.update.macos=true` does: it is off by default.
    private fun environment(os: UpdateOs, arch: String = "x64", launcher: File? = null, appImage: File? = null) =
        InstallEnvironment(os, arch, launcher, appImage, macInApp = true)

    @Test
    fun `an asset is named after the version without its v, the system, the architecture and the format`() {
        val version = checkNotNull(AppVersion.parse("v0.3.0"))
        val names =
            mapOf(
                Triple(UpdateOs.LINUX, "x64", PackageKind.TAR_GZ) to "kotlin-editor-0.3.0-linux-x64.tar.gz",
                Triple(UpdateOs.LINUX, "arm64", PackageKind.APP_IMAGE) to "kotlin-editor-0.3.0-linux-arm64.AppImage",
                Triple(UpdateOs.WINDOWS, "x64", PackageKind.ZIP) to "kotlin-editor-0.3.0-windows-x64.zip",
                Triple(UpdateOs.MAC, "arm64", PackageKind.DMG) to "kotlin-editor-0.3.0-macos-arm64.dmg",
            )
        for ((key, expected) in names) {
            assertEquals(expected, UpdatePlanner.assetName(version, key.first, key.second, key.third))
        }
        val rc = checkNotNull(AppVersion.parse("v1.0.0-rc1"))
        assertEquals(
            "kotlin-editor-1.0.0-rc1-linux-x64.tar.gz",
            UpdatePlanner.assetName(rc, UpdateOs.LINUX, "x64", PackageKind.TAR_GZ),
        )
    }

    @Test
    fun `the system and the architecture are named as in the assets`() {
        assertEquals(UpdateOs.WINDOWS, UpdateOs.fromName("Windows 11"))
        assertEquals(UpdateOs.MAC, UpdateOs.fromName("Mac OS X"))
        assertEquals(UpdateOs.LINUX, UpdateOs.fromName("Linux"))
        assertEquals("x64", UpdateOs.archLabel("amd64"))
        assertEquals("x64", UpdateOs.archLabel("x86_64"))
        assertEquals("arm64", UpdateOs.archLabel("aarch64"))
        assertEquals("riscv64", UpdateOs.archLabel("riscv64"))
    }

    @Test
    fun `the environment is read from the launcher property of jpackage`() {
        val previous = System.getProperty(InstallEnvironment.LAUNCHER_PROPERTY)
        try {
            System.setProperty(InstallEnvironment.LAUNCHER_PROPERTY, "/somewhere/bin/kotlin-editor")
            assertEquals(File("/somewhere/bin/kotlin-editor"), InstallEnvironment.current().launcher)
            System.clearProperty(InstallEnvironment.LAUNCHER_PROPERTY)
            assertEquals(null, InstallEnvironment.current().launcher)
        } finally {
            if (previous != null) System.setProperty(InstallEnvironment.LAUNCHER_PROPERTY, previous)
        }
    }

    @Test
    fun `a tar gz extract the user can write is updated in place with the tar gz`() {
        val launcher = linuxInstall()
        val plan = UpdatePlanner.plan(environment(UpdateOs.LINUX, launcher = launcher), release(TAR))
        val inApp = assertIs<UpdatePlan.InApp>(plan)
        assertEquals(PackageKind.TAR_GZ, inApp.target.kind)
        assertEquals(launcher.parentFile.parentFile, inApp.target.installed)
        assertEquals(listOf(launcher.path), inApp.target.launcher)
        assertEquals("bin${File.separator}kotlin-editor", inApp.target.entry)
        assertEquals(ReleaseAsset(TAR, SIZE), inApp.asset)
        assertEquals(File(inApp.target.installed.parentFile, "kotlin-editor.update"), inApp.target.staging)
        assertEquals(File(inApp.target.installed.parentFile, "kotlin-editor.old"), inApp.target.backup)
    }

    @Test
    fun `a zip extract of Windows is updated with the zip, its spaces kept`() {
        val root = File(base, "tools/Kotlin Editor")
        File(root, "app").mkdirs()
        val launcher = file("tools/Kotlin Editor/Kotlin Editor.exe")
        val plan = UpdatePlanner.plan(environment(UpdateOs.WINDOWS, launcher = launcher), release(ZIP))
        val inApp = assertIs<UpdatePlan.InApp>(plan)
        assertEquals(PackageKind.ZIP, inApp.target.kind)
        assertEquals(root, inApp.target.installed)
        assertEquals(listOf(launcher.path), inApp.target.launcher)
    }

    @Test
    fun `a macOS app is updated with the dmg and started again with open`() {
        val root = File(base, "Applications/Kotlin Editor.app")
        File(root, "Contents/MacOS").mkdirs()
        val launcher = file("Applications/Kotlin Editor.app/Contents/MacOS/Kotlin Editor")
        val plan = UpdatePlanner.plan(environment(UpdateOs.MAC, "arm64", launcher), release(DMG))
        val inApp = assertIs<UpdatePlan.InApp>(plan)
        assertEquals(PackageKind.DMG, inApp.target.kind)
        assertEquals(root, inApp.target.installed)
        assertEquals(listOf("open", "-n", root.path), inApp.target.launcher)
    }

    @Test
    fun `an AppImage the user can write is replaced by the AppImage, whatever the launcher says`() {
        val image = file("apps/kotlin-editor.AppImage")
        val inside = linuxInstall("mount/usr")
        val plan =
            UpdatePlanner.plan(
                environment(UpdateOs.LINUX, launcher = inside, appImage = image),
                release(APPIMAGE),
            )
        val inApp = assertIs<UpdatePlan.InApp>(plan)
        assertEquals(PackageKind.APP_IMAGE, inApp.target.kind)
        assertEquals(image, inApp.target.installed)
        assertEquals(listOf(image.path), inApp.target.launcher)
    }

    @Test
    fun `the AppImage variables of another program, inherited by an IDE it started, are not this IDE's`() {
        val mount = File(base, "mount")
        val inside = File(mount, "bin/kotlin-editor")
        val own = InstallEnvironment.ownAppImage("/apps/ide.AppImage", mount.path, inside)
        assertEquals(File("/apps/ide.AppImage"), own)
        val elsewhere = linuxInstall()
        assertEquals(null, InstallEnvironment.ownAppImage("/apps/terminal.AppImage", mount.path, elsewhere))
        assertEquals(null, InstallEnvironment.ownAppImage("/apps/terminal.AppImage", null, elsewhere))
        assertEquals(null, InstallEnvironment.ownAppImage(null, mount.path, inside))
        assertEquals(null, InstallEnvironment.ownAppImage("/apps/ide.AppImage", mount.path, null))
    }

    @Test
    fun `an AppImage that is gone is a manual update`() {
        val missing = File(base, "gone.AppImage")
        val plan = UpdatePlanner.plan(environment(UpdateOs.LINUX, appImage = missing), release(APPIMAGE))
        assertIs<UpdatePlan.Manual>(plan)
    }

    @Test
    fun `a release without a file for this system is a manual update that says so`() {
        val launcher = linuxInstall()
        val onlyOther = release("kotlin-editor-0.3.0-windows-x64.zip", "kotlin-editor-0.3.0-linux-arm64.tar.gz")
        val plan = UpdatePlanner.plan(environment(UpdateOs.LINUX, launcher = launcher), onlyOther)
        val manual = assertIs<UpdatePlan.Manual>(plan)
        assertTrue("linux x64" in manual.reason, manual.reason)
        assertIs<UpdatePlan.Manual>(UpdatePlanner.plan(environment(UpdateOs.LINUX, launcher = launcher), release()))
    }

    @Test
    fun `a development run, an unknown layout and a root-owned package install are manual updates`() {
        val dev = UpdatePlanner.plan(environment(UpdateOs.LINUX), release(TAR))
        assertIs<UpdatePlan.Manual>(dev)
        val strange = file("strange/launcher")
        val unknown = UpdatePlanner.plan(environment(UpdateOs.LINUX, launcher = strange), release(TAR))
        assertIs<UpdatePlan.Manual>(unknown)
        val deb = File("/opt/kotlin-editor/bin/kotlin-editor")
        val packaged =
            assertIs<UpdatePlan.Manual>(
                UpdatePlanner.plan(environment(UpdateOs.LINUX, launcher = deb), release(TAR)),
            )
        assertTrue("package" in packaged.reason, packaged.reason)
    }

    @Test
    fun `a macOS app is a manual update unless the untried path is asked for`() {
        val root = File(base, "Applications/Kotlin Editor.app")
        File(root, "Contents/MacOS").mkdirs()
        val launcher = file("Applications/Kotlin Editor.app/Contents/MacOS/Kotlin Editor")
        val byDefault = InstallEnvironment(UpdateOs.MAC, "arm64", launcher, appImage = null)
        val manual = assertIs<UpdatePlan.Manual>(UpdatePlanner.plan(byDefault, release(DMG)))
        assertTrue("macOS" in manual.reason, manual.reason)
        assertIs<UpdatePlan.InApp>(UpdatePlanner.plan(byDefault.copy(macInApp = true), release(DMG)))
    }

    @Test
    fun `macOS App Translocation is a manual update`() {
        File(base, "AppTranslocation/ABC/d/Kotlin Editor.app/Contents/MacOS").mkdirs()
        val launcher = file("AppTranslocation/ABC/d/Kotlin Editor.app/Contents/MacOS/Kotlin Editor")
        val plan = UpdatePlanner.plan(environment(UpdateOs.MAC, "arm64", launcher), release(DMG))
        val manual = assertIs<UpdatePlan.Manual>(plan)
        assertTrue("Applications" in manual.reason, manual.reason)
    }

    @Test
    fun `an install in a folder the user cannot write is a manual update`() {
        val launcher = linuxInstall()
        val root = launcher.parentFile.parentFile
        root.setWritable(false)
        try {
            // Running as root, nothing is read-only: the case cannot be built, and root does not need it.
            if (!Files.isWritable(root.toPath())) {
                val plan = UpdatePlanner.plan(environment(UpdateOs.LINUX, launcher = launcher), release(TAR))
                assertIs<UpdatePlan.Manual>(plan)
            }
        } finally {
            root.setWritable(true)
        }
    }

    private companion object {
        const val SIZE = 1_000L
        const val TAR = "kotlin-editor-0.3.0-linux-x64.tar.gz"
        const val ZIP = "kotlin-editor-0.3.0-windows-x64.zip"
        const val DMG = "kotlin-editor-0.3.0-macos-arm64.dmg"
        const val APPIMAGE = "kotlin-editor-0.3.0-linux-x64.AppImage"
    }
}
