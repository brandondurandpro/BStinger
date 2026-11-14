package org.example.kotlineditor.lsp.provision

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.model.Languages
import java.nio.file.Path

/**
 * The language servers of the web: TypeScript and JavaScript (with React's `.tsx` and `.jsx`). Updating a pin:
 * `npm view <package>@<version> dist.integrity dist.tarball` gives the tarball and its SHA-512 (base64); the Node.js
 * minimum is the package's `engines.node`. Run the server by hand on a small project before committing a new pin
 * (the checks of the pull request that added it show how).
 */
object WebServers {
    private const val LICENCE = "MIT (free and open source)"
    private const val NEEDS_NODE = "Needs Node.js on the server machine"
    private const val SERVER = "typescript-language-server"
    private const val SERVER_VERSION = "5.1.3"
    private const val VUE_PLUGIN = "@vue/typescript-plugin"
    private const val VUE_PLUGIN_VERSION = "3.3.14"
    private const val SERVER_SHA_512 =
        "r+pAcYtWdN8tKlYZPwiiHNA2QPjXnI02NrW5Sf2cVM3TRtuQ3V9EKKwOxqwaQ0krsaEXk/CbN90I5erBuf84Vg=="
    private const val SERVER_NODE_MINIMUM = "20"

    // The compiler the server drives (tsserver); 5.x is the line the server supports, 7 is a different, native one.
    private const val TYPESCRIPT_PACKAGE = "typescript"
    private const val TYPESCRIPT_VERSION = "5.9.3"
    private const val TYPESCRIPT_SHA_512 =
        "jl1vZzPDinLr9eUt3J/t7V6FgNEw9QjvBPdysz9KfQDD41fQrC2Y4vKQdiaUpFT4bXlb1RHhLpp8wtm6M5TgSw=="

    private const val TAILWIND_SERVER = "tailwindcss-language-server"
    private const val TAILWIND_PACKAGE = "@tailwindcss/language-server"
    private const val TAILWIND_VERSION = "0.16.0"
    private const val TAILWIND_NODE_MINIMUM = "18"
    private const val TAILWIND_SHA_512 =
        "ko2xr4nlFIkqwJFnnw7hfdpjbsnK0DOk+QEEI51tvKOCF97OG0RU59iGfadoaOoY6I9xkSMbTW+AAwgnGnLH3w=="

    private const val VUE_SERVER = "vue-language-server"
    private const val VUE_SERVER_PACKAGE = "@vue/language-server"
    private const val VUE_SERVER_LOCK = "/pins/vue-language-server.lock"
    private const val VUE_TSC_LOCK = "/pins/vue-tsc.lock"

    private val STRENGTHS =
        listOf(
            "The TypeScript compiler's own analysis: the types are exactly those of tsc",
            "TypeScript, JavaScript, React (.tsx, .jsx) in one server",
            "Uses the TypeScript of the project when it has one",
        )

    private val DRAWBACKS =
        listOf(
            NEEDS_NODE,
            "Memory grows with the size of the project (tsserver keeps its program in memory)",
        )

    // The Vue server, which carries the Vue plugin tsserver loads in Vue projects.
    private val vueBundle =
        PinnedBundle(
            name = VUE_SERVER,
            version = "$VUE_PLUGIN_VERSION+ts$TYPESCRIPT_VERSION",
            packages = vueServerPackages(),
        )

    /** typescript-language-server with the `typescript` it finds in the same `node_modules`. */
    val typescriptLanguageServer =
        LanguageServerCandidate(
            id = SERVER,
            displayName = SERVER,
            language = Languages.TYPESCRIPT,
            licence = LICENCE,
            strengths = STRENGTHS,
            drawbacks = DRAWBACKS,
            requirements = listOf(Requirement(RuntimeKind.Node, SERVER_NODE_MINIMUM)),
            tool = null,
            bundle =
            PinnedBundle(
                name = SERVER,
                version = "$SERVER_VERSION+ts$TYPESCRIPT_VERSION",
                packages = serverPackages(),
            ),
            // In a Vue project tsserver loads the Vue plugin from the Vue server's bundle, and understands .vue files.
            extras = vueBundle,
            optionsIn = { _, extras -> extras?.let(::vuePluginOptions) },
            command = { runtimes, installed -> nodeCommand(runtimes, installed, SERVER, "lib", "cli.mjs") },
            homepage = "https://github.com/typescript-language-server/typescript-language-server",
        )

    /**
     * Tailwind CSS IntelliSense: the class names of the project's own configuration, in the files of the web. A single
     * bundled file with no dependency; it loads the `tailwindcss` of the project, so it works once `npm install` ran.
     */
    val tailwindLanguageServer =
        LanguageServerCandidate(
            id = TAILWIND_SERVER,
            displayName = TAILWIND_SERVER,
            language = Languages.TAILWIND,
            licence = LICENCE,
            strengths =
            listOf(
                "Completes the class names of the project's own tailwind configuration",
                "Reports conflicting classes and unknown ones",
            ),
            drawbacks =
            listOf(
                NEEDS_NODE,
                "Needs tailwindcss installed in the project (npm install)",
            ),
            requirements = listOf(Requirement(RuntimeKind.Node, TAILWIND_NODE_MINIMUM)),
            tool = null,
            bundle =
            PinnedBundle(
                name = TAILWIND_SERVER,
                version = TAILWIND_VERSION,
                packages = listOf(npm(TAILWIND_PACKAGE, TAILWIND_VERSION, TAILWIND_SHA_512)),
            ),
            command = { runtimes, installed ->
                nodeCommand(runtimes, installed, TAILWIND_PACKAGE, "bin", "tailwindcss-language-server")
            },
            homepage = "https://github.com/tailwindlabs/tailwindcss-intellisense",
        )

    /**
     * Vue Language Tools in hybrid mode: the template, style and component features of `.vue` files, beside the
     * TypeScript server, which it asks for the types through the client (see `TsserverRelay`). It carries its own
     * copy of `typescript`, whose `lib` it is told to use.
     */
    val vueLanguageServer =
        LanguageServerCandidate(
            id = VUE_SERVER,
            displayName = VUE_SERVER,
            language = Languages.VUE_SERVER,
            licence = LICENCE,
            strengths =
            listOf(
                "Completes the HTML elements, components, directives and styles of a .vue template",
                "Reports the errors of templates and styles, with the types from the TypeScript server",
            ),
            drawbacks =
            listOf(
                NEEDS_NODE,
                "Works with the TypeScript server: both run on a Vue project",
            ),
            requirements = listOf(Requirement(RuntimeKind.Node, SERVER_NODE_MINIMUM)),
            tool = null,
            bundle = vueBundle,
            command = { runtimes, installed ->
                nodeCommand(runtimes, installed, VUE_SERVER_PACKAGE, "bin", "vue-language-server.js")
            },
            optionsIn = { bundle, _ -> vueServerOptions(bundle) },
            homepage = "https://github.com/vuejs/language-tools",
        )

    /**
     * `vue-tsc`, the type checker of Vue projects (it checks `.ts` files as `tsc` does): not a language server but
     * a command that checks the whole project, run on request. Its own copy of `typescript`.
     */
    val vueTsc =
        PinnedBundle(
            name = "vue-tsc",
            version = "$VUE_PLUGIN_VERSION+ts$TYPESCRIPT_VERSION",
            packages =
            listOf(npm(TYPESCRIPT_PACKAGE, TYPESCRIPT_VERSION, TYPESCRIPT_SHA_512)) + lockedPackages(VUE_TSC_LOCK),
        )

    /** All of them, in the order the choice panel shows them. */
    val all: List<LanguageServerCandidate> =
        listOf(typescriptLanguageServer, vueLanguageServer, tailwindLanguageServer)

    private fun npm(name: String, version: String, integrity: String): PinnedPackage {
        val file = name.substringAfterLast('/')
        val url = "https://registry.npmjs.org/$name/-/$file-$version.tgz"
        return PinnedPackage(name, url, Digest(Digest.SHA_512, integrity))
    }

    /** `node <vue-tsc> --noEmit --pretty false`, in the project to check. */
    fun vueTscCommand(runtimes: Map<RuntimeKind, Path>, installed: Path): List<String> =
        nodeCommand(runtimes, installed, "vue-tsc", "bin", "vue-tsc.js").dropLast(1) +
            listOf("--noEmit", "--pretty", "false")

    // `node <installed>/node_modules/<package>/<path…> --stdio`
    private fun nodeCommand(
        runtimes: Map<RuntimeKind, Path>,
        installed: Path?,
        packageName: String,
        vararg path: String,
    ): List<String> {
        val modules = checkNotNull(installed).resolve("node_modules")
        val entry = path.fold(modules.resolve(packageName)) { directory, part -> directory.resolve(part) }
        val node = runtimes.getValue(RuntimeKind.Node)
        return listOf(node.toString(), entry.toString(), "--stdio")
    }

    private fun serverPackages(): List<PinnedPackage> {
        val server = npm(SERVER, SERVER_VERSION, SERVER_SHA_512)
        val compiler = npm(TYPESCRIPT_PACKAGE, TYPESCRIPT_VERSION, TYPESCRIPT_SHA_512)
        return listOf(server, compiler)
    }

    private fun vueServerPackages(): List<PinnedPackage> {
        val compiler = npm(TYPESCRIPT_PACKAGE, TYPESCRIPT_VERSION, TYPESCRIPT_SHA_512)
        return listOf(compiler) + lockedPackages(VUE_SERVER_LOCK)
    }

    private fun vueServerOptions(bundle: Path): JsonElement {
        val library = bundle.resolve("node_modules").resolve(TYPESCRIPT_PACKAGE).resolve("lib")
        return buildJsonObject {
            putJsonObject("typescript") { put("tsdk", library.toString()) }
        }
    }

    private fun vuePluginOptions(bundle: Path): JsonElement =
        buildJsonObject {
            putJsonArray("plugins") {
                add(
                    buildJsonObject {
                        put("name", VUE_PLUGIN)
                        put("location", bundle.toString())
                        putJsonArray("languages") { add("vue") }
                    },
                )
            }
        }

    // The packages of a lock in the resources (see tools/pin-npm-bundle): location, version and SHA-512 per line.
    private fun lockedPackages(resource: String): List<PinnedPackage> {
        val stream = checkNotNull(WebServers::class.java.getResourceAsStream(resource)) { "Missing $resource" }
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map(::lockedPackage).toList()
        }
    }

    private fun lockedPackage(line: String): PinnedPackage {
        val (location, version, integrity) = line.split('\t')
        val name = location.substringAfterLast("node_modules/")
        return npm(name, version, integrity).copy(location = location)
    }
}
