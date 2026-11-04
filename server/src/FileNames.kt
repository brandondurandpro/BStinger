package org.example.kotlineditor.server

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

private val WINDOWS = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

// Devices: `CON`, `aux.txt` and `nul.tar.gz` all name the device, not a file.
private val WINDOWS_DEVICE = Regex("(CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³])(\\..*)?", RegexOption.IGNORE_CASE)
private const val WINDOWS_FORBIDDEN = "<>:\"|?*"
private const val FIRST_PRINTABLE = ' '

/**
 * Fails unless the disk of the server can name a file [name] as typed ([name] may hold directories, `a/b.txt`). On
 * Windows a device name would open the device, and a final dot or space would be dropped without a word.
 */
internal fun requireValidName(name: String) {
    require(name.isNotBlank()) { "Invalid name: $name" }
    if (WINDOWS) name.split('/', '\\').filter { it != "." && it != ".." }.forEach(::requireWindowsName)
}

private fun requireWindowsName(segment: String) {
    require(!WINDOWS_DEVICE.matches(segment)) { "Windows keeps the name $segment for a device" }
    val forbidden = segment.firstOrNull { it in WINDOWS_FORBIDDEN || it < FIRST_PRINTABLE }
    require(forbidden == null) { "Windows does not allow '$forbidden' in a name: $segment" }
    val trailing = segment.endsWith('.') || segment.endsWith(' ')
    require(!trailing) { "Windows drops a dot or a space at the end of a name: $segment" }
}

/**
 * Moves [source] to [target], also when only the case of the name changes: on a disk that ignores case (Windows,
 * macOS) [target] is then [source] itself, which a direct move leaves as it is.
 */
internal fun moveRenaming(source: Path, target: Path): Path {
    val caseOnly = target.name != source.name && target.name.equals(source.name, ignoreCase = true)
    val sameFile = caseOnly && Files.exists(target) && Files.isSameFile(source, target)
    if (!sameFile) return Files.move(source, target)
    val temp = Files.move(source, source.resolveSibling(".${source.name}.${System.nanoTime()}.rename"))
    return try {
        Files.move(temp, target)
    } catch (failed: IOException) {
        Files.move(temp, source)
        throw failed
    }
}
