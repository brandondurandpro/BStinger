package org.example.kotlineditor.state

import org.example.kotlineditor.model.FileEntry

/** A kind of run configuration a feature suggests for a project: the Gradle and Amper wrappers, and others. */
interface RunConfigurationsSource {
    /** The configurations the files at the root of the project ([entries]) suggest. */
    suspend fun configurations(entries: List<FileEntry>, windows: Boolean): List<RunConfiguration>
}
