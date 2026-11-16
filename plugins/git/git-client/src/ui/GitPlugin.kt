package org.example.kotlineditor.ui

import androidx.compose.ui.input.key.Key
import org.example.kotlineditor.plugin.ActionContext
import org.example.kotlineditor.plugin.StripGroup
import org.example.kotlineditor.plugin.ToolWindowContribution
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.GitProjectView
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowDefaults

/** The Git tool window (local changes): refreshes the status when it appears. */
fun gitToolWindow(): ToolWindowContribution =
    ToolWindowContribution(
        defaults = ToolWindowDefaults(ToolWindow.Git, Dock.Left),
        tag = GitTags.TOOL_GIT,
        group = StripGroup.Top,
        rank = 1,
        onShown = { host -> GitProjectView(host).git.refreshStatus() },
        icon = Lucide.History,
        content = { host, drag, modifier -> GitPanel(GitProjectView(host), drag, modifier) },
    )

/** The Branches tool window (history): loads the branches and the selected history when it appears. */
fun branchesToolWindow(): ToolWindowContribution =
    ToolWindowContribution(
        defaults = ToolWindowDefaults(ToolWindow.Branches, Dock.Bottom),
        tag = GitTags.TOOL_BRANCHES,
        group = StripGroup.Last,
        rank = 3,
        // Shown again: the branches and the history may have changed meanwhile. The first time, the panel itself
        // selects the current branch once it is known (`BranchesPanel`).
        onShown = { host ->
            val git = GitProjectView(host).git
            git.refreshBranches()
            if (git.history.selectedRef != null) git.history.reload()
        },
        icon = Lucide.GitBranch,
        content = { host, drag, modifier -> BranchesPanel(GitProjectView(host), drag, modifier) },
    )

/** Alt+9 toggles the Git window. */
fun gitShortcuts(): List<KeyBinding<ActionContext>> =
    listOf(binding(setOf(Key.Nine), alt = true) { project.toggleToolWindow(ToolWindow.Git) })
