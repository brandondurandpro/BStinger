package org.example.kotlineditor.model

/** What a hierarchy shows: the calls of a function, or the types of a class. */
enum class HierarchyKind {
    /** Who calls a function, what it calls. */
    Calls,

    /** What a class extends or implements, what extends or implements it. */
    Types,
}

/** A side of a hierarchy: who calls a function or what it calls, the parents of a type or its children. */
enum class HierarchyDirection(
    /** The hierarchy this side belongs to. */
    val kind: HierarchyKind,
) {
    /** The functions calling it. */
    Callers(HierarchyKind.Calls),

    /** The functions it calls. */
    Callees(HierarchyKind.Calls),

    /** The classes and interfaces it extends or implements. */
    Supertypes(HierarchyKind.Types),

    /** The classes extending or implementing it. */
    Subtypes(HierarchyKind.Types),
}

/** A function or a type of a hierarchy (Ctrl+Alt+H, Ctrl+H). */
data class HierarchyItem(
    /** Its name. */
    val name: String,
    /** What the server adds to it: its class, its signature; null when nothing. */
    val detail: String?,
    /** Where its name is, with the text of its line. */
    val location: SourceLocation,
    /** The item as the language server described it, sent back to ask for its callers or its subtypes. */
    val data: String,
    /** For calls: how many times it calls, or is called, on that side. */
    val calls: Int = 0,
)

/** The items of a hierarchy: the symbol at the caret, or the children of an item. */
data class HierarchyResult(
    /** Whether a server answered. */
    val status: FeatureStatus,
    /** In the server's order. */
    val items: List<HierarchyItem>,
) {
    /** Answers shared by every client. */
    companion object {
        /** No server could answer. */
        val NotReady = HierarchyResult(FeatureStatus.NotReady, emptyList())
    }
}
