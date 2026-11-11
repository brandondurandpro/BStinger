package org.example.kotlineditor.ui

import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.model.GitResetMode
import org.example.kotlineditor.state.GitHost
import org.example.kotlineditor.state.HistoryDialog
import org.example.kotlineditor.state.createBranchAt
import org.example.kotlineditor.state.createTag
import org.example.kotlineditor.state.rebaseOnto
import org.example.kotlineditor.state.renameBranch
import org.example.kotlineditor.state.resetTo

private const val SHORT = 8

// The dialogs the menus of the Branches window open: what to ask, and the action to run on confirmation.

internal fun newBranchDialog(project: GitHost, startPoint: String) =
    HistoryDialog("New branch", "Created at ${startPoint.take(SHORT)} and checked out.", "", "Create") { name, done ->
        project.git.createBranchAt(startPoint, name, done)
    }

internal fun renameDialog(project: GitHost, name: String) =
    HistoryDialog("Rename branch", null, name, "Rename") { newName, done ->
        project.git.renameBranch(name, newName, done)
    }

internal fun newTagDialog(project: GitHost, commit: GitCommit) =
    HistoryDialog("New tag", "On ${commit.shortHash}: ${commit.subject}", "", "Create") { name, done ->
        project.git.createTag(name, commit.hash, done)
    }

internal fun rebaseDialog(project: GitHost, onto: String) =
    HistoryDialog("Rebase onto $onto", "Rewrites the commits of the current branch.", null, "Rebase") { _, done ->
        project.git.rebaseOnto(onto, done)
    }

internal fun hardResetDialog(project: GitHost, commit: GitCommit) =
    HistoryDialog(
        "Hard reset to ${commit.shortHash}",
        "Moves the current branch there and discards every uncommitted change.",
        null,
        "Reset",
        danger = true,
    ) { _, done -> project.git.resetTo(commit.hash, GitResetMode.Hard, done) }
