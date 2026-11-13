package org.example.kotlineditor.state

/** The name of the project the conversations belong to: its folder. */
val AgentState.projectName: String get() = project.root.name
