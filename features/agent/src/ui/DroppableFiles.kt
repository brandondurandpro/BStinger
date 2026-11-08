package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.io.File

/** Takes the files dropped on this element from the desktop (a file manager) and gives them to [onDrop]. */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.droppableFiles(onDrop: (List<File>) -> Unit): Modifier =
    dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
        target =
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val transferable = event.awtTransferable
                val data = runCatching { transferable.getTransferData(DataFlavor.javaFileListFlavor) }.getOrNull()
                val files = (data as? List<*>)?.filterIsInstance<File>().orEmpty()
                onDrop(files)
                return files.isNotEmpty()
            }
        },
    )
