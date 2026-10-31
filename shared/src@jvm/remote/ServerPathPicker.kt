package org.example.kotlineditor.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import org.example.kotlineditor.model.DirectoryPicker
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

/** Asks for a project directory that lives on the remote server (the local file chooser would browse this disk). */
class ServerPathPicker(private val destination: String) : DirectoryPicker {
    override suspend fun pickDirectory(): String? =
        suspendCancellableCoroutine { continuation ->
            SwingUtilities.invokeLater {
                val path =
                    JOptionPane.showInputDialog(
                        null,
                        "Project directory on $destination:",
                        "Open Project",
                        JOptionPane.PLAIN_MESSAGE,
                    )
                continuation.resume(path?.trim()?.takeIf { it.isNotEmpty() })
            }
        }
}
