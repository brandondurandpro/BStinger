package org.example.kotlineditor.e2e

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserDefinedFileAttributeView

/** A file of [SampleProject.withUnusualFiles], and what is unusual about it. */
class UnusualFile(val path: String, val unusual: String)

private val WINDOWS = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

private const val LOCKED_DIRECTORY = "unusual/locked"

/**
 * The text files of [SampleProject.withUnusualFiles], which a save must keep as they are but for their text; each
 * only where the disk can have it (POSIX permissions and links, a group of the user, extended attributes).
 */
fun SampleProject.unusualFiles(): List<UnusualFile> =
    buildList {
        add(UnusualFile("unusual/crlf.txt", "CRLF line endings"))
        add(UnusualFile("unusual/no-final-newline.txt", "no final newline"))
        add(UnusualFile("unusual/bom.txt", "a UTF-8 byte order mark"))
        add(UnusualFile("unusual/latin1.php", "ISO-8859-1 bytes, not UTF-8"))
        add(UnusualFile("unusual/données é.txt", "a name with spaces and accents"))
        if (isPosix()) {
            add(UnusualFile("unusual/run.sh", "the executable bit"))
            add(UnusualFile("unusual/link.txt", "a symbolic link to another file"))
            add(UnusualFile("unusual/hard.txt", "a second hard link"))
            add(UnusualFile("unusual/back\\slash.txt", "a backslash in its name"))
            add(UnusualFile("$LOCKED_DIRECTORY/inside.txt", "a directory the IDE cannot write"))
        }
        if (WINDOWS) {
            add(UnusualFile("unusual/hidden.txt", "the hidden DOS attribute"))
            add(UnusualFile("unusual/access.txt", "an access rule of its own"))
        }
        if (secondaryGroup() != null) add(UnusualFile("unusual/other-group.txt", "another group of the user"))
        if (userAttributesSupported()) {
            add(
                UnusualFile("unusual/attributes.txt", "a user extended attribute (a stream)"),
            )
        }
    }

/**
 * [SampleProject.standard] plus the files real projects have and the default fixtures do not (see [unusualFiles]):
 * the features that write files are tested on them too, since they break what the tests of clean files never see.
 */
fun SampleProject.Companion.withUnusualFiles(): SampleProject =
    standard().apply {
        file("unusual/crlf.txt", "first\r\nsecond\r\n")
        file("unusual/no-final-newline.txt", "first\nsecond")
        binary("unusual/bom.txt", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "first\n".toByteArray())
        binary("unusual/latin1.php", "<?php\n// café crème\n".toByteArray(Charsets.ISO_8859_1))
        file("unusual/données é.txt", "first\n")
        if (isPosix()) addPosixFiles()
        if (WINDOWS) addWindowsFiles()
        secondaryGroup()?.let { group ->
            val file = file("unusual/other-group.txt", "first\n")
            Files.getFileAttributeView(file, PosixFileAttributeView::class.java).setGroup(group)
        }
        if (userAttributesSupported()) {
            val file = file("unusual/attributes.txt", "first\n")
            val view = Files.getFileAttributeView(file, UserDefinedFileAttributeView::class.java)
            view.write("user.origin", ByteBuffer.wrap("fixture".toByteArray()))
        }
    }

private fun SampleProject.addPosixFiles() {
    val script = file("unusual/run.sh", "#!/bin/sh\necho first\n")
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
    file("unusual/target.txt", "first\n")
    Files.createSymbolicLink(root.resolve("unusual/link.txt"), root.resolve("unusual/target.txt").fileName)
    val hard = file("unusual/hard.txt", "first\n")
    Files.createLink(root.resolve("unusual/hard-twin.txt"), hard)
    file("unusual/back\\slash.txt", "first\n")
    file("$LOCKED_DIRECTORY/inside.txt", "first\n")
    Files.setPosixFilePermissions(root.resolve(LOCKED_DIRECTORY), PosixFilePermissions.fromString("r-xr-xr-x"))
}

private fun SampleProject.addWindowsFiles() {
    val hidden = file("unusual/hidden.txt", "first\n")
    Files.setAttribute(hidden, "dos:hidden", true)
    val access = file("unusual/access.txt", "first\n")
    val view = Files.getFileAttributeView(access, AclFileAttributeView::class.java)
    val everyone = root.fileSystem.userPrincipalLookupService.lookupPrincipalByName("Everyone")
    val rule =
        AclEntry
            .newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(everyone)
            .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES)
            .build()
    view.acl = listOf(rule) + view.acl
}

/** Gives back the write permission the fixture took from its directories, so that the project can be deleted. */
fun SampleProject.unlockUnusualFiles() {
    val locked = root.resolve(LOCKED_DIRECTORY)
    if (isPosix() && Files.isDirectory(locked)) {
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
    }
}

private fun SampleProject.isPosix(): Boolean =
    !WINDOWS && root.fileSystem.supportedFileAttributeViews().contains("posix")

// Probes next to the project, on the same disk but outside it. A group of the user other than the group its files
// get, if it has one (`id -G`).
private fun SampleProject.secondaryGroup(): GroupPrincipal? {
    if (!isPosix()) return null
    val probe = Files.createTempFile(root.parent, ".group", ".probe")
    try {
        val view = Files.getFileAttributeView(probe, PosixFileAttributeView::class.java)
        val primary = view.readAttributes().group()
        val lookup = root.fileSystem.userPrincipalLookupService
        val groups = groupsOfUser().mapNotNull { runCatching { lookup.lookupPrincipalByGroupName(it) }.getOrNull() }
        return groups.firstOrNull { it != primary && runCatching { view.setGroup(it) }.isSuccess }
    } finally {
        Files.deleteIfExists(probe)
    }
}

private fun groupsOfUser(): List<String> =
    runCatching {
        val process = ProcessBuilder("id", "-Gn").redirectErrorStream(true).start()
        val names =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
                .split(' ')
        if (process.waitFor() == 0) names.filter { it.isNotBlank() } else emptyList()
    }.getOrDefault(emptyList())

// Whether the disk of the project keeps user extended attributes (ext4, btrfs, tmpfs since Linux 6.6; NTFS streams).
private fun SampleProject.userAttributesSupported(): Boolean {
    val probe: Path = Files.createTempFile(root.parent, ".attributes", ".probe")
    return try {
        val view = Files.getFileAttributeView(probe, UserDefinedFileAttributeView::class.java)
        view != null && runCatching { view.write("user.probe", ByteBuffer.wrap(byteArrayOf(1))) }.isSuccess
    } finally {
        Files.deleteIfExists(probe)
    }
}
