package org.example.kotlineditor.state

/** The Git state of a project (branch, status, changes, history), as [ProjectHost.service] gives it to the Git UI. */
val GIT_SERVICE = ServiceKey<GitState>("git")
