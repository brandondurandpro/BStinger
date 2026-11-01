package org.example.kotlineditor.server

import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.ProjectSearch
import org.example.kotlineditor.model.SearchQuery
import org.example.kotlineditor.model.TextMatch
import org.example.kotlineditor.model.findAllInFiles

/** Every match of a Find in Files of [text], sorted by file then line (they are streamed in no particular order). */
suspend fun ProjectSearch.find(root: FileEntry, text: String, ignoreCase: Boolean, limit: Int): List<TextMatch> =
    findAllInFiles(root, SearchQuery(text, ignoreCase), limit)
        .sortedWith(compareBy<TextMatch> { it.entry.path }.thenBy { it.line })
