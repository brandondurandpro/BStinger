package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.example.kotlineditor.state.MergeKind
import org.example.kotlineditor.state.MergeState
import org.example.kotlineditor.state.TabStatus
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.GitTags

/** The lines of the file the conflict tests merge: [base], then each branch changes some of them. */
internal val MERGE_BASE = textOf("one", "two", "three", "four", "five", "six", "")

/** Ours (the current branch) changes the second and the fourth line. */
internal val MERGE_OURS = textOf("one", "two ours", "three", "four ours", "five", "six", "")

/** Theirs changes the second and the sixth line: only the second one conflicts. */
internal val MERGE_THEIRS = textOf("one", "two theirs", "three", "four", "five", "six theirs", "")

/**
 * Leaves the project in the middle of a merge: [path] holds [base] on the default branch and on `feature`, then the
 * branches change it to [ours] and [theirs], and `feature` is merged, which Git stops on the conflict.
 */
internal fun SampleProject.startConflictingMerge(
    path: String,
    base: String = MERGE_BASE,
    ours: String = MERGE_OURS,
    theirs: String = MERGE_THEIRS,
) {
    file(path, base)
    commitAll()
    val current = git("rev-parse", "--abbrev-ref", "HEAD").trim()
    git("checkout", "-q", "-b", "feature")
    file(path, theirs)
    git("commit", "-q", "-am", "theirs")
    git("checkout", "-q", current)
    file(path, ours)
    git("commit", "-q", "-am", "ours")
    val merged = runCatching { git("merge", "--no-edit", "feature") }
    check(merged.isFailure) { "The merge of the feature branch was expected to conflict" }
}

/** The paths Git still lists as unmerged. */
internal fun SampleProject.unmergedPaths(): List<String> =
    git("diff", "--name-only", "--diff-filter=U").lines().filter { it.isNotBlank() }

/** Opens the Git panel and clicks the conflicted [path]: its merge tab opens; returns the merge it shows. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.openMergeFromGitPanel(app: E2eApp, path: String): MergeState {
    onNodeWithTag(AppTags.TOOL_GIT).performClick()
    awaitTag(GitTags.gitChange(path)).performClick()
    awaitTag(EditorTags.MERGE)
    awaitCondition {
        app.project.editor.activeTab
            ?.status is TabStatus.Merge
    }
    return mergeOf(app)
}

internal fun mergeOf(app: E2eApp): MergeState {
    val status =
        app.project.editor.activeTab
            ?.status as? TabStatus.Merge
    return checkNotNull(status) { "The active tab is not a merge" }.state
}

/** The index of the first conflict of the merge. */
internal fun MergeState.firstConflict(): Int = chunks.indexOfFirst { it.kind == MergeKind.Conflict }
