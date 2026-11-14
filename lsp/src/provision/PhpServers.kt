package org.example.kotlineditor.lsp.provision

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The PHP language servers offered to the user. Updating a pin:
 * - phpactor: latest release of github.com/phpactor/phpactor; the SHA-256 is the `digest` GitHub shows for the
 *   `phpactor.phar` asset (`gh api repos/phpactor/phpactor/releases/latest`), the PHP minimum is `require.php` of
 *   the release's composer.json;
 * - intelephense: `https://registry.npmjs.org/intelephense/latest` gives `dist.tarball` and `dist.integrity`
 *   (SHA-512, base64), and `bin.intelephense` the entry point.
 * Download each file once and check its digest by hand before committing a new pin.
 */
object PhpServers {
    /** Language identifier of PHP. */
    const val LANGUAGE = "php"

    private const val PHPACTOR = "phpactor"
    private const val INTELEPHENSE = "intelephense"
    private const val PHPACTOR_VERSION = "2026.10.07.0"
    private const val PHPACTOR_SHA_256 = "b28bf6166e016d539fc2f2aec18ef918b12590e436a6f1ccbdf30b5bbdbdb16b"
    private const val PHPACTOR_PHP_MINIMUM = "8.2"

    // What bin/phpactor checks before it starts; it exits with code 255 without one of them.
    private val PHPACTOR_PHP_EXTENSIONS = listOf("mbstring", "posix", "tokenizer")

    private const val INTELEPHENSE_VERSION = "1.18.5"
    private const val INTELEPHENSE_SHA_512 =
        "dqCH1YNCRlHGBLND+iUFjBJlGwM4pPimX2jm8AaP/6K2WZNM2K2+E8dOWCD3ZYMP2zC2ICBD9Soe2oxKtU9d9A=="
    private const val INTELEPHENSE_NODE_MINIMUM = "18"

    private val PHPACTOR_STRENGTHS =
        listOf(
            "Free and open source: every feature is included",
            "Rename and many refactorings included",
            "Only needs PHP, already installed for a PHP project",
        )

    private val PHPACTOR_DRAWBACKS =
        listOf(
            "Slower indexing of large projects",
            "Type inference less precise on large projects",
        )

    private val INTELEPHENSE_STRENGTHS =
        listOf(
            "Generally the most complete and fastest analysis",
            "Fast indexing of large projects",
            "Widely used, often the default PHP server of other editors",
        )

    private val INTELEPHENSE_DRAWBACKS =
        listOf(
            "Rename, find implementations and part of the refactorings need a paid licence",
            "Needs Node.js on the server machine",
        )

    /**
     * What phpactor leaves off unless its client asks (its configuration, given as `initializationOptions`): the
     * inlay hints, with the type of each assigned variable (`$user: User`) as well as the parameter names.
     */
    val PHPACTOR_OPTIONS =
        buildJsonObject {
            put("language_server_worse_reflection.inlay_hints.enable", true)
            put("language_server_worse_reflection.inlay_hints.types", true)
            put("language_server_worse_reflection.inlay_hints.params", true)
        }

    /** phpactor: free, MIT, runs on PHP. */
    val phpactor =
        LanguageServerCandidate(
            id = PHPACTOR,
            displayName = PHPACTOR,
            language = LANGUAGE,
            licence = "MIT (free and open source)",
            strengths = PHPACTOR_STRENGTHS,
            drawbacks = PHPACTOR_DRAWBACKS,
            requirements = listOf(Requirement(RuntimeKind.Php, PHPACTOR_PHP_MINIMUM, PHPACTOR_PHP_EXTENSIONS)),
            tool =
            PinnedTool(
                name = PHPACTOR,
                version = PHPACTOR_VERSION,
                url = "https://github.com/phpactor/phpactor/releases/download/$PHPACTOR_VERSION/phpactor.phar",
                digest = Digest(Digest.SHA_256, PHPACTOR_SHA_256),
                fileName = "phpactor.phar",
            ),
            command = { runtimes, phar ->
                listOf(runtimes.getValue(RuntimeKind.Php).toString(), checkNotNull(phar).toString(), "language-server")
            },
            initializationOptions = PHPACTOR_OPTIONS,
            homepage = "https://github.com/phpactor/phpactor",
        )

    /** intelephense: proprietary with a free core, runs on Node.js. */
    val intelephense =
        LanguageServerCandidate(
            id = INTELEPHENSE,
            displayName = INTELEPHENSE,
            language = LANGUAGE,
            licence = "Proprietary, free core (premium features need a licence key)",
            strengths = INTELEPHENSE_STRENGTHS,
            drawbacks = INTELEPHENSE_DRAWBACKS,
            requirements = listOf(Requirement(RuntimeKind.Node, INTELEPHENSE_NODE_MINIMUM)),
            tool =
            PinnedTool(
                name = INTELEPHENSE,
                version = INTELEPHENSE_VERSION,
                url = "https://registry.npmjs.org/intelephense/-/intelephense-$INTELEPHENSE_VERSION.tgz",
                digest = Digest(Digest.SHA_512, INTELEPHENSE_SHA_512),
                fileName = "intelephense-$INTELEPHENSE_VERSION.tgz",
                unpack = Unpack.NpmTarball,
            ),
            command = { runtimes, pkg ->
                listOf(
                    runtimes.getValue(RuntimeKind.Node).toString(),
                    checkNotNull(pkg).resolve("lib").resolve("intelephense.js").toString(),
                    "--stdio",
                )
            },
            withoutInlayHints =
            "intelephense gives inlay hints only with a Premium licence, and never the type of a variable: " +
                "phpactor gives both for free.",
            homepage = "https://github.com/bmewburn/vscode-intelephense",
        )

    /** Both, in the order the choice panel shows them. */
    val all: List<LanguageServerCandidate> = listOf(phpactor, intelephense)
}
