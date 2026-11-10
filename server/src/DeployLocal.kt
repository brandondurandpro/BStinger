package org.example.kotlineditor.server

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

// A relative destination is relative to the project, which is what a user typing `dist` means.
internal fun copyToLocalFolder(plan: DeployPlan, destination: String) {
    val base = plan.root.resolve(destination).normalize()
    val landings = plan.items.map { it to base.resolve(it.target).normalize() }
    for ((item, landing) in landings) {
        val inside = Files.isDirectory(item.path, LinkOption.NOFOLLOW_LINKS) && landing.startsWith(item.path)
        require(!inside) { "The destination $landing is inside ${plan.root.relativize(item.path).ifEmptyRoot()}" }
    }
    Files.createDirectories(base)
    for ((item, landing) in landings) Files.walkFileTree(item.path, Copy(item.path, landing))
}

private fun Path.ifEmptyRoot(): String = toString().ifEmpty { "the project" }

// Copies a tree to [landing], keeping times, permissions and links; a file replaces the file already there.
private class Copy(private val source: Path, private val landing: Path) : SimpleFileVisitor<Path>() {
    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (dir.fileName?.toString() == VCS_DIRECTORY) return FileVisitResult.SKIP_SUBTREE
        Files.createDirectories(destinationOf(dir))
        return FileVisitResult.CONTINUE
    }

    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        val destination = destinationOf(file)
        Files.createDirectories(destination.parent)
        Files.copy(
            file,
            destination,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
            LinkOption.NOFOLLOW_LINKS,
        )
        return FileVisitResult.CONTINUE
    }

    private fun destinationOf(path: Path): Path = landing.resolve(source.relativize(path).toString())
}
