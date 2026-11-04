package org.example.kotlineditor.syntax

/** Languages known by the editor: drives highlighting (client) and outlines (server). */
enum class Language(
    /** Words colored as keywords. */
    val keywords: Set<String>,
    /** Whether `//` and block comments exist. */
    val slashComments: Boolean,
    /** Whether `#` starts a comment. */
    val hashComments: Boolean,
) {
    /** Kotlin sources and scripts. */
    Kotlin(
        words(
            """
            package import class interface fun val var object typealias constructor init companion if else when for
            while do try catch finally throw return break continue in is as this super null true false data sealed
            enum abstract open override private protected public internal inner lateinit inline suspend operator infix
            const annotation value where by out reified tailrec external expect actual vararg noinline crossinline final
            """.trimIndent(),
        ),
        slashComments = true,
        hashComments = false,
    ),

    /** Java sources. */
    Java(
        words(
            """
            abstract assert boolean break byte case catch char class const continue default do double else enum extends
            final finally float for goto if implements import instanceof int interface long native new package private
            protected public return short static strictfp super switch synchronized this throw throws transient try void
            volatile while true false null var record sealed permits yield
            """.trimIndent(),
        ),
        slashComments = true,
        hashComments = false,
    ),

    /** Languages with a C-like syntax (JavaScript, TypeScript, C, Go, Rust, Swift...). */
    CLike(
        words(
            """
            break case catch class const continue default do else enum export extends false finally for function if
            import in instanceof interface let new null return static struct super switch this throw true try typeof var
            void while async await fn func go impl mut pub use mod match type def package int char float double long
            unsigned bool auto namespace
            """.trimIndent(),
        ),
        slashComments = true,
        hashComments = false,
    ),

    /** Scripts and configuration files commented with `#`. */
    Script(
        words(
            """
            and as assert break class continue def del elif else except finally for from if import in is lambda not or
            pass raise return try while with yield None True False true false then fi esac done function echo export
            local case
            """.trimIndent(),
        ),
        slashComments = false,
        hashComments = true,
    ),

    /** Anything else: no highlighting. */
    Plain(emptySet(), slashComments = false, hashComments = false),
    ;

    /** Detection of the language of a file. */
    companion object {
        private val byExtension: Map<String, Language> =
            buildMap {
                words("kt kts").forEach { put(it, Kotlin) }
                put("java", Java)
                words(
                    "js jsx ts tsx c h cpp hpp cc cs go rs swift scala groovy gradle dart css scss json",
                ).forEach { put(it, CLike) }
                words("py sh bash zsh yaml yml toml properties rb conf ini").forEach { put(it, Script) }
            }

        /** The language of the file named [name], from its extension. */
        fun fromFileName(name: String): Language = byExtension[name.substringAfterLast('.', "").lowercase()] ?: Plain
    }
}

private fun words(text: String): Set<String> =
    text
        .split(' ', '\n')
        .filter { it.isNotBlank() }
        .map { it.trim() }
        .toSet()
