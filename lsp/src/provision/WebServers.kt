package org.example.kotlineditor.lsp.provision

/**
 * The language servers of the web: TypeScript and JavaScript (with React's `.tsx` and `.jsx`). Updating a pin:
 * `npm view <package>@<version> dist.integrity dist.tarball` gives the tarball and its SHA-512 (base64); the Node.js
 * minimum is the package's `engines.node`. Run the server by hand on a small project before committing a new pin
 * (the checks of the pull request that added it show how).
 */
object WebServers {
    /** Language of `.ts`, `.tsx`, `.js` and `.jsx` files: one server serves them all. */
    const val TYPESCRIPT = "typescript"

    private const val SERVER = "typescript-language-server"
    private const val SERVER_VERSION = "5.1.3"
    private const val SERVER_SHA_512 =
        "r+pAcYtWdN8tKlYZPwiiHNA2QPjXnI02NrW5Sf2cVM3TRtuQ3V9EKKwOxqwaQ0krsaEXk/CbN90I5erBuf84Vg=="
    private const val SERVER_NODE_MINIMUM = "20"

    // The compiler the server drives (tsserver); 5.x is the line the server supports, 7 is a different, native one.
    private const val TYPESCRIPT_PACKAGE = "typescript"
    private const val TYPESCRIPT_VERSION = "5.9.3"
    private const val TYPESCRIPT_SHA_512 =
        "jl1vZzPDinLr9eUt3J/t7V6FgNEw9QjvBPdysz9KfQDD41fQrC2Y4vKQdiaUpFT4bXlb1RHhLpp8wtm6M5TgSw=="

    private val STRENGTHS =
        listOf(
            "The TypeScript compiler's own analysis: the types are exactly those of tsc",
            "TypeScript, JavaScript, React (.tsx, .jsx) in one server",
            "Uses the TypeScript of the project when it has one",
        )

    private val DRAWBACKS =
        listOf(
            "Needs Node.js on the server machine",
            "Memory grows with the size of the project (tsserver keeps its program in memory)",
        )

    /** typescript-language-server with the `typescript` it finds in the same `node_modules`. */
    val typescriptLanguageServer =
        LanguageServerCandidate(
            id = SERVER,
            displayName = SERVER,
            language = TYPESCRIPT,
            licence = "MIT (free and open source)",
            strengths = STRENGTHS,
            drawbacks = DRAWBACKS,
            requirements = listOf(Requirement(RuntimeKind.Node, SERVER_NODE_MINIMUM)),
            tool = null,
            bundle =
            PinnedBundle(
                name = SERVER,
                version = "$SERVER_VERSION+ts$TYPESCRIPT_VERSION",
                packages =
                listOf(
                    npm(SERVER, SERVER_VERSION, SERVER_SHA_512),
                    npm(TYPESCRIPT_PACKAGE, TYPESCRIPT_VERSION, TYPESCRIPT_SHA_512),
                ),
            ),
            command = { runtimes, installed ->
                val modules = checkNotNull(installed).resolve("node_modules")
                val entry = modules.resolve(SERVER).resolve("lib").resolve("cli.mjs")
                val node = runtimes.getValue(RuntimeKind.Node)
                listOf(node.toString(), entry.toString(), "--stdio")
            },
        )

    /** All of them, in the order the choice panel shows them. */
    val all: List<LanguageServerCandidate> = listOf(typescriptLanguageServer)

    private fun npm(name: String, version: String, integrity: String): PinnedPackage {
        val file = name.substringAfterLast('/')
        val url = "https://registry.npmjs.org/$name/-/$file-$version.tgz"
        return PinnedPackage(name, url, Digest(Digest.SHA_512, integrity))
    }
}
