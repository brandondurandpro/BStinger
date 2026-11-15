package org.example.kotlineditor.unit

import org.example.kotlineditor.e2e.FakeInstall
import org.example.kotlineditor.e2e.FakeRelease
import org.example.kotlineditor.e2e.updateTestOs
import org.example.kotlineditor.state.AppVersion
import org.example.kotlineditor.state.AvailableRelease
import org.example.kotlineditor.state.PackageKind
import org.example.kotlineditor.state.ReleaseAsset
import org.example.kotlineditor.state.UpdateOs
import org.example.kotlineditor.state.UpdatePlan
import org.example.kotlineditor.state.UpdatePlanner
import org.example.kotlineditor.state.UpdateStaging
import org.example.kotlineditor.state.UpdateTarget
import org.example.kotlineditor.state.ZipExtraction
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The unpacking of a downloaded update next to the install: dates kept, nothing outside the target, complete. */
class UpdateStagingTest {
    private val base: File = Files.createTempDirectory("update-staging").toFile()
    private val installs = ArrayList<FakeInstall>()

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
        installs.forEach(FakeInstall::close)
    }

    private fun zipOf(name: String, entries: List<Triple<String, String, Long>>): File {
        val file = File(base, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((entryName, content, modified) in entries) {
                val entry = ZipEntry(entryName)
                entry.lastModifiedTime = FileTime.fromMillis(modified)
                zip.putNextEntry(entry)
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun seconds(file: File): Long = file.lastModified() / MILLIS

    @Test
    fun `a zip is extracted without its top directory, each file and directory with the date of its entry`() {
        val zip =
            zipOf(
                "a.zip",
                listOf(
                    Triple("Top/", "", DIRECTORY_DATE),
                    Triple("Top/app/", "", DIRECTORY_DATE),
                    Triple("Top/app/app.jar", "jar", JAR_DATE),
                    Triple("Top/launcher", "run", JAR_DATE),
                ),
            )
        val into = File(base, "out")
        ZipExtraction.extract(zip.toPath(), into.toPath(), stripComponents = 1)
        assertEquals("jar", File(into, "app/app.jar").readText())
        assertEquals("run", File(into, "launcher").readText())
        assertEquals(JAR_DATE / MILLIS, seconds(File(into, "app/app.jar")))
        assertEquals(DIRECTORY_DATE / MILLIS, seconds(File(into, "app")))
        assertFalse(File(into, "Top").exists())
    }

    @Test
    fun `an entry that leaves the target directory is refused, nothing is written outside`() {
        val refused =
            listOf("Top/../../evil.txt", "/absolute/evil.txt", "Top/..\\evil.txt", "../evil.txt", "C:/evil.txt")
        for ((index, name) in refused.withIndex()) {
            val zip = zipOf("evil$index.zip", listOf(Triple(name, "bad", JAR_DATE)))
            val into = File(base, "target$index/inside")
            assertFailsWith<IOException>(
                name,
            ) { ZipExtraction.extract(zip.toPath(), into.toPath(), stripComponents = 1) }
            assertFalse(File(base, "target$index/evil.txt").exists(), name)
            assertFalse(File(base, "evil.txt").exists(), name)
        }
    }

    // The staged target of a fake install, for a fake release of version 0.3.0.
    private fun targetOf(install: FakeInstall): UpdateTarget {
        val version = checkNotNull(AppVersion.parse("v0.3.0"))
        val asset = ReleaseAsset(FakeRelease.assetName("0.3.0"), 1)
        val plan = UpdatePlanner.plan(install.environment, AvailableRelease("v0.3.0", version, listOf(asset)))
        return (plan as UpdatePlan.InApp).target
    }

    private fun install(): FakeInstall = FakeInstall.create().also { installs.add(it) }

    @Test
    fun `an archive is staged next to the install with the dates of its files, the install is untouched`() {
        val install = install()
        val archive = FakeRelease.archive("0.3.0", base)
        val staged = UpdateStaging.stage(targetOf(install), archive)
        assertEquals(File(install.root.parentFile, install.root.name + ".update"), staged)
        assertEquals("0.3.0", File(staged, FakeRelease.VERSION_FILE).readText().trim())
        assertEquals("old", install.version())
        val jar = File(staged, "${FakeRelease.appDirectory}/app.jar")
        assertEquals(OLD_JAR_DATE / MILLIS, seconds(jar), "the AOT cache needs the dates of the jars")
        if (updateTestOs != UpdateOs.WINDOWS) assertTrue(File(staged, FakeRelease.UNIX_LAUNCHER).canExecute())
    }

    @Test
    fun `an AppImage is staged next to the old one, executable, and the old one is untouched`() {
        val dir = File(base, "apps").apply { mkdirs() }
        val image = File(dir, "kotlin-editor.AppImage").apply { writeText("old") }
        val target = UpdateTarget(PackageKind.APP_IMAGE, image, listOf(image.path), entry = "")
        val archive = FakeRelease.archive("0.3.0", base, assetKind = PackageKind.APP_IMAGE)
        val staged = UpdateStaging.stage(target, archive)
        assertEquals(File(dir, "kotlin-editor.AppImage.update"), staged)
        assertEquals(archive.readText(), staged.readText())
        if (updateTestOs != UpdateOs.WINDOWS) assertTrue(staged.canExecute())
        assertEquals("old", image.readText())
    }

    @Test
    fun `the new AppImage gets the permissions of the one it replaces, not those of the download`() {
        if (updateTestOs == UpdateOs.WINDOWS) return
        val dir = File(base, "apps").apply { mkdirs() }
        val image = File(dir, "kotlin-editor.AppImage").apply { writeText("old") }
        val shared = PosixFilePermissions.fromString("rwxr-xr-x")
        Files.setPosixFilePermissions(image.toPath(), shared)
        val archive = FakeRelease.archive("0.3.0", base, assetKind = PackageKind.APP_IMAGE)
        Files.setPosixFilePermissions(archive.toPath(), PosixFilePermissions.fromString("rw-------"))
        val target = UpdateTarget(PackageKind.APP_IMAGE, image, listOf(image.path), entry = "")
        val staged = UpdateStaging.stage(target, archive)
        assertEquals(shared, Files.getPosixFilePermissions(staged.toPath()))
    }

    @Test
    fun `a staging left by a previous try is replaced`() {
        val install = install()
        val target = targetOf(install)
        target.staging.mkdirs()
        File(target.staging, "stale.txt").writeText("stale")
        val staged = UpdateStaging.stage(target, FakeRelease.archive("0.3.0", base))
        assertFalse(File(staged, "stale.txt").exists())
    }

    @Test
    fun `a corrupted archive fails and leaves no staging`() {
        val install = install()
        val target = targetOf(install)
        val archive = File(base, FakeRelease.assetName("0.3.0"))
        archive.writeText("this is not an archive")
        assertFailsWith<IOException> { UpdateStaging.stage(target, archive) }
        assertFalse(target.staging.exists())
        assertEquals("old", install.version())
    }

    @Test
    fun `an archive without the launcher is refused as incomplete`() {
        val install = install()
        val target = targetOf(install)
        val archive = FakeRelease.archive("0.3.0", base, withLauncher = false)
        val failure = assertFailsWith<IOException> { UpdateStaging.stage(target, archive) }
        assertTrue("incomplete" in failure.message.orEmpty(), failure.message)
        assertFalse(target.staging.exists())
    }

    @Test
    fun `too little free space is refused with the sizes, before anything is written`() {
        val needed = UpdateStaging.requiredBytes(PackageKind.TAR_GZ, 100L * MEGABYTE)
        assertTrue(needed > 3 * 100L * MEGABYTE)
        assertTrue(UpdateStaging.requiredBytes(PackageKind.APP_IMAGE, 100L * MEGABYTE) < needed)
        val failure = assertFailsWith<IOException> { UpdateStaging.requireRoom(MEGABYTE, needed, base) }
        assertTrue("Not enough disk space" in failure.message.orEmpty(), failure.message)
        UpdateStaging.requireRoom(needed, needed, base)
    }

    private companion object {
        const val MILLIS = 1_000L
        const val MEGABYTE = 1024L * 1024L
        const val JAR_DATE = 1_600_000_000_000L
        const val DIRECTORY_DATE = 1_500_000_000_000L
        const val OLD_JAR_DATE = 1_577_836_800_000L
    }
}
