package org.example.kotlineditor.ui

import androidx.compose.foundation.lazy.LazyListState
import org.example.kotlineditor.state.SearchHit

/** The hits of one file, in the order the search found them. */
internal class ResultGroup(val relativePath: String, val name: String, val hits: List<SearchHit>)

// A row of the grouped results: the header of a file, or one of its hits with its place among the visible hits.
internal sealed interface ResultRow {
    class Header(val group: ResultGroup, val collapsed: Boolean) : ResultRow

    class Hit(val hit: SearchHit, val visibleIndex: Int) : ResultRow
}

/** The hits grouped by file, files in order of first hit. */
internal fun groupByFile(hits: List<SearchHit>): List<ResultGroup> {
    val byPath = LinkedHashMap<String, MutableList<SearchHit>>()
    for (hit in hits) byPath.getOrPut(hit.relativePath) { mutableListOf() }.add(hit)
    return byPath.map { (path, fileHits) -> ResultGroup(path, fileHits.first().entry.name, fileHits) }
}

/** The hits the keyboard walks through: those of the files that are not folded. */
internal fun visibleHits(groups: List<ResultGroup>, collapsed: Collection<String>): List<SearchHit> =
    groups.filter { it.relativePath !in collapsed }.flatMap { it.hits }

internal fun rowsOf(groups: List<ResultGroup>, collapsed: Collection<String>): List<ResultRow> {
    val rows = mutableListOf<ResultRow>()
    var visibleIndex = 0
    for (group in groups) {
        val folded = group.relativePath in collapsed
        rows.add(ResultRow.Header(group, folded))
        if (folded) continue
        for (hit in group.hits) {
            rows.add(ResultRow.Hit(hit, visibleIndex))
            visibleIndex++
        }
    }
    return rows
}

// Scrolls to the selected hit when the keyboard moved it out of the rows on screen.
internal suspend fun revealSelected(listState: LazyListState, rows: List<ResultRow>, selected: Int) {
    val target = rows.indexOfFirst { it is ResultRow.Hit && it.visibleIndex == selected }
    val visible = listState.layoutInfo.visibleItemsInfo
    val first = visible.firstOrNull()?.index
    val last = visible.lastOrNull()?.index
    val onScreen = first != null && last != null && target in first until last
    if (target >= 0 && !onScreen) listState.scrollToItem(maxOf(0, target - 2))
}
