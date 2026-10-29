package org.example.kotlineditor

import kotlinx.coroutines.suspendCancellableCoroutine
import org.example.kotlineditor.model.DirectoryPicker
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

/** Native chooser for the project directory (the embedded server runs on this machine). */
class SwingDirectoryPicker : DirectoryPicker {
    override suspend fun pickDirectory(): String? =
        suspendCancellableCoroutine { continuation ->
            SwingUtilities.invokeLater {
                val chooser =
                    JFileChooser(System.getProperty("user.home")).apply {
                        dialogTitle = "Open File or Project"
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        isAcceptAllFileFilterUsed = false
                    }
                val result = chooser.showOpenDialog(null)
                continuation.resume(
                    if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null,
                )
            }
        }
}
