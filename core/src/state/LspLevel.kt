package org.example.kotlineditor.state

/** How much the language servers do: none, the essentials, or everything. */
enum class LspLevel(val title: String, val description: String) {
    Off("Off", "No language server: syntax highlighting only."),
    Basic(
        "Basic",
        "Completion, navigation, problems and usages; no inlay hints, semantic colors, code lenses or links.",
    ),
    Full("Full", "Everything the language servers offer."),
    ;

    companion object {
        /** The level saved as [value]; null when it is blank or unknown. */
        fun fromSaved(value: String?): LspLevel? = entries.firstOrNull { it.name == value }
    }
}

/** Where the level in force was chosen: the most precise choice wins. */
enum class LspLevelSource(val title: String) {
    Conversation("conversation"),
    Project("project"),
    Global("global"),
}
