package org.example.kotlineditor.server

import org.example.kotlineditor.server.ripgrep.RipgrepBinary
import org.example.kotlineditor.server.ripgrep.RipgrepPlatform
import org.example.kotlineditor.server.ripgrep.RipgrepProvisioner
import org.example.kotlineditor.server.ripgrep.RipgrepRelease
import org.example.kotlineditor.server.ripgrep.RipgrepSettings
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.fail

/** Lookup order, verification and download of the ripgrep executable. */
class RipgrepProvisionerTest {
    private val root: Path = Files.createTempDirectory("ripgrep-provisioner")
    private val fetched = ArrayList<String>()

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun settings(executable: String? = null, bundled: Path? = null, download: Boolean = true) =
        RipgrepSettings(executable, bundled, toolsDirectory = root.resolve("tools"), download = download)

    private fun failingFetch(url: String): InputStream {
        fetched += url
        throw IOException("offline")
    }

    @Test
    fun `knows the official build of each supported platform`() {
        assertEquals(RipgrepPlatform.LinuxX64, RipgrepPlatform.current("Linux", "amd64"))
        assertEquals(RipgrepPlatform.LinuxArm64, RipgrepPlatform.current("Linux", "aarch64"))
        assertEquals(RipgrepPlatform.MacX64, RipgrepPlatform.current("Mac OS X", "x86_64"))
        assertEquals(RipgrepPlatform.MacArm64, RipgrepPlatform.current("Mac OS X", "aarch64"))
        assertEquals(RipgrepPlatform.WindowsX64, RipgrepPlatform.current("Windows 11", "amd64"))
        assertNull(RipgrepPlatform.current("Linux", "riscv64"))
        assertNull(RipgrepPlatform.current("FreeBSD", "amd64"))
        assertEquals(
            "https://github.com/BurntSushi/ripgrep/releases/download/${RipgrepRelease.VERSION}/" +
                "ripgrep-${RipgrepRelease.VERSION}-x86_64-pc-windows-msvc.zip",
            RipgrepRelease.archiveUrl(RipgrepPlatform.WindowsX64),
        )
        assertEquals(
            "ripgrep-${RipgrepRelease.VERSION}-aarch64-unknown-linux-musl/rg",
            RipgrepPlatform.LinuxArm64.archiveEntry,
        )
    }

    @Test
    fun `an unusable configured executable disables ripgrep instead of trying other copies`() {
        val missing = root.resolve("nope").toString()
        val provisioner = RipgrepProvisioner(settings(executable = missing), fetch = ::failingFetch)
        assertNull(provisioner.locate())
        assertEquals(emptyList(), fetched)
    }

    @Test
    fun `a download that does not match the pinned checksum is never installed`() {
        val platform = RipgrepPlatform.LinuxX64
        val provisioner =
            RipgrepProvisioner(settings(), platform) { url ->
                fetched += url
                "not the ripgrep archive".byteInputStream()
            }
        assertNull(provisioner.locate())
        assertEquals(listOf(RipgrepRelease.archiveUrl(platform)), fetched)
        assertEquals(emptyList(), provisioner.cacheDirectory.listDirectoryEntries(), "nothing left behind")
    }

    @Test
    fun `without network nor download nothing is found`() {
        assertNull(RipgrepProvisioner(settings(), fetch = ::failingFetch).locate())
        assertEquals(1, fetched.size)
        assertNull(RipgrepProvisioner(settings(download = false), fetch = { fail("no download allowed") }).locate())
    }

    @Test
    fun `the bundled copy is used when verified, otherwise ignored`() {
        val rg = TestRipgrep.require()
        val platform = checkNotNull(RipgrepPlatform.current())
        val bundled = root.resolve("dist/ripgrep").createDirectories()
        val copy = Files.copy(rg.path, bundled.resolve(platform.executableName))
        copy.toFile().setExecutable(true)
        val found = RipgrepProvisioner(settings(bundled = bundled), fetch = ::failingFetch).locate()
        assertEquals(RipgrepBinary(copy, RipgrepBinary.Origin.Bundled), found)

        Files.write(copy, byteArrayOf(0), StandardOpenOption.APPEND)
        assertNull(RipgrepProvisioner(settings(bundled = bundled), fetch = ::failingFetch).locate())
        assertEquals(1, fetched.size, "the tampered copy is skipped and a download attempted")
    }

    @Test
    fun `a tampered downloaded copy is replaced`() {
        val provisioner = RipgrepProvisioner(settings(), fetch = ::failingFetch)
        val platform = checkNotNull(RipgrepPlatform.current())
        provisioner.cacheDirectory.createDirectories()
        provisioner.cacheDirectory.resolve(platform.executableName).writeText("#!/bin/sh\necho ripgrep 15.2.0\n")
        assertNull(provisioner.locate())
        assertEquals(1, fetched.size)
    }

    @Test
    fun `downloads, verifies and installs the official build`() {
        TestRipgrep.require() // Skips offline, like the other tests needing ripgrep.
        val provisioner = RipgrepProvisioner(settings())
        val found = provisioner.locate()
        val platform = checkNotNull(RipgrepPlatform.current())
        val installed = provisioner.cacheDirectory.resolve(platform.executableName)
        assertEquals(RipgrepBinary(installed, RipgrepBinary.Origin.Downloaded), found)
        assertEquals(platform.binarySha256, RipgrepProvisioner.sha256(found!!.path))
        assertEquals(listOf(found.path), provisioner.cacheDirectory.listDirectoryEntries())
        assertFalse(found.path.toString().endsWith(".part"))
    }
}
