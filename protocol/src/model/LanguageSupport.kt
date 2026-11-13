package org.example.kotlineditor.model

private const val WHITE = 0xFFFFFFFFL
private const val BLACK = 0xFF000000L
private const val PHP_PURPLE = 0xFF777BB4L
private const val TYPESCRIPT_BLUE = 0xFF3178C6L
private const val JAVASCRIPT_YELLOW = 0xFFF7DF1EL
private const val VUE_GREEN = 0xFF41B883L
private const val HTML_ORANGE = 0xFFE44D26L
private const val CSS_BLUE = 0xFF1572B6L
private const val KOTLIN_PURPLE = 0xFF8F6CF5L
private const val ORANGE = 0xFFE08855L
private const val PYTHON_BLUE = 0xFF3C82DCL
private const val MARKDOWN_BLUE = 0xFF5E9FD5L
private const val JSON_YELLOW = 0xFFD9B43AL
private const val YAML_RED = 0xFFDB5C5CL
private const val TOML_GREY = 0xFF9AA7B0L
private const val GRADLE_TEAL = 0xFF5FB2A7L
private const val SHELL_GREEN = 0xFF6AAB73L

/** The shape a file type's icon is drawn in. */
enum class IconShape {
    /** A rounded square with the label in it (TypeScript, JavaScript, Kotlin…). */
    Square,

    /** A shield (HTML, CSS). */
    Shield,

    /** An ellipse (PHP). */
    Oval,

    /** The two chevrons of the Vue logo, without a label. */
    Vue,
}

/** The icon of the files of a language in the project tree and the tabs. Colors are ARGB. */
data class LanguageIcon(
    /** The text in the icon (`TS`, `K`); empty for a shape that has none. */
    val label: String,
    /** The color of the shape. */
    val color: Long,
    /** What the icon is drawn as. */
    val shape: IconShape = IconShape.Square,
    /** The color of the [label]. */
    val labelColor: Long = WHITE,
)

/**
 * How a language server sees a language: what its files are called in `didOpen`, which server serves them, and what
 * the IDE does with them.
 */
data class LspSupport(
    /** The language identifier the servers are told in `didOpen` (`typescriptreact` for a `.tsx`). */
    val identifier: String,
    /** The language of the server that serves it: one server serves several identifiers (`typescript`). */
    val group: String,
    /** The languages of the servers that serve its files beside the one of its own group. */
    val companions: List<String> = emptyList(),
    /** File whose directory is the root of the server of the [group] (`composer.json`), null when the project is. */
    val rootMarker: String? = null,
    /** Whether the IDE follows the file with code insight (usage counts, hints, problems), not only completion. */
    val insight: Boolean = true,
)

/**
 * Everything the IDE declares about a language it supports, in one place. A language is a `object` below, listed in
 * [all]: a test fails when one is missing from the list, and others check each facet (see
 * `docs/ADDING_A_LANGUAGE.md` for what else a new language needs: its tree-sitter grammar, its language server).
 *
 * A language with no [lsp] is only known for its icon (and its colors, from the syntax module): Kotlin, Markdown…
 */
sealed class LanguageSupport(
    /** Name for people. */
    val name: String,
    /** The extensions of its files, lowercase, without the dot. */
    val extensions: Set<String>,
    /** Its icon. */
    val icon: LanguageIcon,
    /** What its language server is told, null when the IDE has no language server for it. */
    val lsp: LspSupport? = null,
) {
    /** PHP, served by Intelephense or Phpactor. */
    object Php : LanguageSupport(
        "PHP",
        setOf("php", "phtml"),
        LanguageIcon("php", PHP_PURPLE, IconShape.Oval),
        LspSupport("php", "php", rootMarker = "composer.json"),
    )

    /** TypeScript, served by typescript-language-server. */
    object TypeScript : LanguageSupport(
        "TypeScript",
        setOf("ts", "mts", "cts"),
        LanguageIcon("TS", TYPESCRIPT_BLUE),
        LspSupport("typescript", Languages.TYPESCRIPT, companions = listOf(Languages.TAILWIND)),
    )

    /** TSX files: TypeScript with JSX. */
    object TypeScriptReact : LanguageSupport(
        "TypeScript React",
        setOf("tsx"),
        LanguageIcon("TS", TYPESCRIPT_BLUE),
        LspSupport("typescriptreact", Languages.TYPESCRIPT, companions = listOf(Languages.TAILWIND)),
    )

    /** JavaScript, served by the TypeScript server. */
    object JavaScript : LanguageSupport(
        "JavaScript",
        setOf("js", "mjs", "cjs"),
        LanguageIcon("JS", JAVASCRIPT_YELLOW, labelColor = BLACK),
        LspSupport("javascript", Languages.TYPESCRIPT, companions = listOf(Languages.TAILWIND)),
    )

    /** JSX files: JavaScript with JSX. */
    object JavaScriptReact : LanguageSupport(
        "JavaScript React",
        setOf("jsx"),
        LanguageIcon("JS", JAVASCRIPT_YELLOW, labelColor = BLACK),
        LspSupport("javascriptreact", Languages.TYPESCRIPT, companions = listOf(Languages.TAILWIND)),
    )

    /** Single-file components, served by the TypeScript server with the Vue plugin and the Vue server. */
    object Vue : LanguageSupport(
        "Vue",
        setOf("vue"),
        LanguageIcon("", VUE_GREEN, IconShape.Vue),
        LspSupport("vue", Languages.TYPESCRIPT, companions = listOf(Languages.VUE_SERVER, Languages.TAILWIND)),
    )

    /** HTML: completion and hover only, from the companion servers. */
    object Html : LanguageSupport(
        "HTML",
        setOf("html", "htm"),
        LanguageIcon("5", HTML_ORANGE, IconShape.Shield),
        LspSupport("html", "html", companions = listOf(Languages.TAILWIND), insight = false),
    )

    /** CSS: completion and hover only, from the companion servers. */
    object Css : LanguageSupport(
        "CSS",
        setOf("css"),
        LanguageIcon("3", CSS_BLUE, IconShape.Shield),
        LspSupport("css", "css", companions = listOf(Languages.TAILWIND), insight = false),
    )

    /** Kotlin: an icon and syntax colors, no language server. */
    object Kotlin : LanguageSupport("Kotlin", setOf("kt", "kts"), LanguageIcon("K", KOTLIN_PURPLE))

    /** Java: an icon and syntax colors, no language server. */
    object Java : LanguageSupport("Java", setOf("java"), LanguageIcon("J", ORANGE))

    /** Python: an icon and syntax colors, no language server. */
    object Python : LanguageSupport("Python", setOf("py"), LanguageIcon("Py", PYTHON_BLUE))

    /** Markdown: an icon, no language server. */
    object Markdown : LanguageSupport("Markdown", setOf("md"), LanguageIcon("M", MARKDOWN_BLUE))

    /** JSON: an icon, no language server. */
    object Json : LanguageSupport("JSON", setOf("json"), LanguageIcon("{}", JSON_YELLOW))

    /** XML: an icon, no language server. */
    object Xml : LanguageSupport(
        "XML",
        setOf(
            "xml",
            "xsd",
            "xsl",
            "xslt",
            "svg",
            "plist",
            "wsdl",
            "rss",
            "atom",
            "xaml",
            "fxml",
            "csproj",
            "props",
            "targets",
            "resx",
        ),
        LanguageIcon("<>", ORANGE),
    )

    /** YAML: an icon, no language server. */
    object Yaml : LanguageSupport("YAML", setOf("yaml", "yml"), LanguageIcon("Y", YAML_RED))

    /** TOML: an icon, no language server. */
    object Toml : LanguageSupport("TOML", setOf("toml"), LanguageIcon("T", TOML_GREY))

    /** Gradle scripts: an icon, no language server. */
    object Gradle : LanguageSupport("Gradle", setOf("gradle"), LanguageIcon("G", GRADLE_TEAL))

    /** Shell scripts: an icon, no language server. */
    object Shell : LanguageSupport("Shell", setOf("sh"), LanguageIcon(">", SHELL_GREEN))

    /** Windows batch files: an icon, no language server. */
    object Batch : LanguageSupport("Batch", setOf("bat"), LanguageIcon(">", SHELL_GREEN))

    /** The declared languages, and the lookup of a file's. */
    companion object {
        /**
         * Every language, in the order they are shown. Read when first used, not when the class loads: the objects
         * extend this class, and one of them loaded first would find the list asking for it while it is loading.
         */
        val all: List<LanguageSupport> by lazy {
            listOf(
                Php,
                TypeScript,
                TypeScriptReact,
                JavaScript,
                JavaScriptReact,
                Vue,
                Html,
                Css,
                Kotlin,
                Java,
                Python,
                Markdown,
                Json,
                Xml,
                Yaml,
                Toml,
                Gradle,
                Shell,
                Batch,
            )
        }

        private val byExtension: Map<String, LanguageSupport> by lazy {
            all.flatMap { language -> language.extensions.map { it to language } }.toMap()
        }

        /** The language of the file named [fileName], by its extension (whatever its case); null when unknown. */
        fun forFileName(fileName: String): LanguageSupport? {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return byExtension[extension]
        }
    }
}
