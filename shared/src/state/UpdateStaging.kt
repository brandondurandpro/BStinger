package org.example.kotlineditor.state

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Puts the new version of the IDE next to the install ([UpdateTarget.staging]), complete and checked, while the IDE
 * still runs: whatever fails here is shown to the user, nothing is installed yet. Extraction keeps the modification
 * times of the files (the AOT cache of the IDE is only valid while the jars keep their dates), and the links.
 */
object UpdateStaging {
    // An unpacked app image is about twice its archive; the rest is margin for the files of the system.
    private const val UNPACKED_FACTOR = 3L
    private const val BYTES_PER_MEGABYTE = 1024L * 1024L
    private const val MARGIN = 64L * BYTES_PER_MEGABYTE
    private const val MAX_OUTPUT = 400
    private const val HDIUTIL = "hdiutil"

    /** Extracts the downloaded [archive] into the staging place of [target]; returns it. */
    fun stage(target: UpdateTarget, archive: File): File {
        val staging = target.staging
        val parent = staging.absoluteFile.parentFile
        requireRoom(parent.usableSpace, requiredBytes(target.kind, archive.length()), parent)
        deleteTree(staging)
        try {
            extract(target, archive, staging)
            validate(target, staging)
        } catch (failure: IOException) {
            deleteTree(staging)
            throw failure
        }
        return staging
    }

    /** The free space needed in the folder of the install to stage an [archiveSize] archive of [kind]. */
    fun requiredBytes(kind: PackageKind, archiveSize: Long): Long {
        val factor = if (kind == PackageKind.APP_IMAGE) 1L else UNPACKED_FACTOR
        return archiveSize * factor + MARGIN
    }

    /** Fails with a message the user can act on when [free] bytes are fewer than [required]. */
    fun requireRoom(free: Long, required: Long, directory: File) {
        if (free >= required) return
        val needed = required / BYTES_PER_MEGABYTE
        val available = free / BYTES_PER_MEGABYTE
        throw IOException(
            "Not enough disk space in $directory to install the update: $needed MB needed, $available MB free.",
        )
    }

    private fun extract(target: UpdateTarget, archive: File, staging: File) {
        when (target.kind) {
            PackageKind.APP_IMAGE -> copyAppImage(archive, target.installed, staging)
            PackageKind.TAR_GZ -> untar(archive, staging)
            PackageKind.ZIP -> ZipExtraction.extract(archive.toPath(), staging.toPath(), stripComponents = 1)
            PackageKind.DMG -> copyFromDiskImage(archive, staging)
        }
    }

    // The new file gets the permissions of the one it replaces (a download is only readable by its owner), and
    // stays executable whatever they were.
    private fun copyAppImage(archive: File, installed: File, staging: File) {
        Files.copy(archive.toPath(), staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val permissions = runCatching { Files.getPosixFilePermissions(installed.toPath()) }.getOrNull()
        if (permissions != null) Files.setPosixFilePermissions(staging.toPath(), permissions)
        if (!staging.setExecutable(true, false)) throw IOException("Cannot make ${staging.name} executable.")
    }

    // The system tar keeps dates and links; the top directory of the archive is the install itself.
    private fun untar(archive: File, staging: File) {
        Files.createDirectories(staging.toPath())
        run(listOf("tar", "-xzf", archive.path, "-C", staging.path, "--strip-components=1"))
    }

    // The .app of the image is copied with ditto (links, attributes, signatures), and the image is always detached.
    private fun copyFromDiskImage(archive: File, staging: File) {
        val mount = Files.createTempDirectory(archive.absoluteFile.parentFile.toPath(), "mount").toFile()
        try {
            run(listOf(HDIUTIL, "attach", "-nobrowse", "-readonly", "-mountpoint", mount.path, archive.path))
            try {
                val bundle = mount.listFiles { file -> file.name.endsWith(".app") }?.firstOrNull()
                if (bundle == null) throw IOException("The disk image has no application.")
                run(listOf("ditto", bundle.path, staging.path))
            } finally {
                detach(mount)
            }
        } finally {
            mount.delete()
        }
    }

    private fun detach(mount: File) {
        val polite = runCatching { run(listOf(HDIUTIL, "detach", mount.path)) }
        if (polite.isFailure) runCatching { run(listOf(HDIUTIL, "detach", "-force", mount.path)) }
    }

    // A tree without its launcher is a truncated or foreign archive, never to be swapped in.
    private fun validate(target: UpdateTarget, staging: File) {
        val complete =
            when (target.kind) {
                PackageKind.APP_IMAGE -> staging.isFile && staging.length() > 0
                PackageKind.DMG -> File(staging, target.entry).isDirectory
                else -> File(staging, target.entry).isFile
            }
        if (!complete) {
            throw IOException(
                "The downloaded update is incomplete: it has no ${target.entry.ifEmpty { "program" }}.",
            )
        }
    }

    // Runs a command, and fails with its output when it fails.
    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        val code = process.waitFor()
        if (code != 0) throw IOException("${command.first()} failed (exit code $code): ${output.takeLast(MAX_OUTPUT)}")
    }

    /** Deletes [file] and what it holds, whatever it is; nothing when it does not exist. */
    fun deleteTree(file: File) {
        if (!Files.isSymbolicLink(file.toPath())) file.deleteRecursively() else file.delete()
    }
}
