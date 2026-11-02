import io.github.treesitter.jtreesitter.Language;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Regenerates the tree-sitter grammar catalog of the editor (`syntax/src/treesitter/GrammarCatalog.kt`) and
 * its highlight queries (`syntax/resources/tree-sitter/<id>/highlights.scm`), see `tools/tree-sitter/README.md`.
 *
 * For each grammar below: downloads its prebuilt jar (bonede/tree-sitter-ng on Maven Central), records its SHA-256,
 * its platforms and its entry point, fetches its highlight query (the grammar's own repository at the built version,
 * else Helix's), and keeps only the patterns that compile against the grammar with the predicates the editor applies.
 * Run with `tools/tree-sitter/update-grammars`.
 */
public class UpdateGrammars {
    static final String MAVEN = "https://repo1.maven.org/maven2/io/github/bonede/";
    /** Helix revision the fallback queries come from. */
    static final String HELIX = "ba40e547426b0f9896c8bdc699a4ab11f2b37dbc";
    static final Path CACHE = Path.of(System.getProperty("user.home"), ".cache", "kotlin-editor-tools", "grammars");
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    /** Predicates the editor evaluates (jtreesitter's); patterns using another one are dropped. */
    static final Set<String> PREDICATES = Set.of(
        "eq?", "not-eq?", "any-eq?", "any-not-eq?", "match?", "not-match?", "any-match?", "any-not-match?",
        "any-of?", "not-any-of?", "set!");

    record Grammar(
        String id, String name, String extensions, String fileNames, String upstream, String upstreamRef,
        String helix, String before) {
    }

    static final List<Grammar> GRAMMARS = new ArrayList<>();

    /**
     * [id] is the artifact `tree-sitter-<id>`; [upstream] `owner/repo` holds `queries/highlights.scm` at the tag of the
     * built version (`v%s` or `%s`, or [upstreamRef] when given); [helix] names its Helix query, used without upstream
     * query; [before] is the id of the grammar whose upstream query the language extends (C++ extends C).
     */
    static void g(String id, String name, String extensions, String fileNames, String upstream, String helix) {
        GRAMMARS.add(new Grammar(id, name, extensions, fileNames, upstream, null, helix, null));
    }

    static void g(String id, String name, String extensions, String fileNames, String upstream, String ref,
                  String helix, String before) {
        GRAMMARS.add(new Grammar(id, name, extensions, fileNames, upstream, ref, helix, before));
    }

    static {
        g("ada", "Ada", "adb ads", "", null, "ada");
        g("agda", "Agda", "agda", "", "tree-sitter/tree-sitter-agda", "agda");
        g("bash", "Bash", "sh bash zsh ksh", ".bashrc .bash_profile .zshrc .profile PKGBUILD", "tree-sitter/tree-sitter-bash", "bash");
        g("c", "C", "c h", "", "tree-sitter/tree-sitter-c", "c");
        g("cpp", "C++", "cpp cc cxx c++ hpp hh hxx h++ ino", "", "tree-sitter/tree-sitter-cpp", null, "cpp", "c");
        g("c-sharp", "C#", "cs csx", "", "tree-sitter/tree-sitter-c-sharp", "c-sharp");
        g("capnp", "Cap'n Proto", "capnp", "", null, "capnp");
        g("clojure", "Clojure", "clj cljs cljc edn bb", "", null, "clojure");
        g("cmake", "CMake", "cmake", "CMakeLists.txt", null, "cmake");
        g("commonlisp", "Common Lisp", "lisp lsp cl asd", "", null, "common-lisp");
        g("css", "CSS", "css", "", "tree-sitter/tree-sitter-css", "css");
        g("d", "D", "d di", "", null, "d");
        g("dart", "Dart", "dart", "", null, "dart");
        g("dockerfile", "Dockerfile", "dockerfile", "Dockerfile Containerfile", null, "dockerfile");
        g("dot", "DOT", "dot gv", "", null, "dot");
        g("elisp", "Emacs Lisp", "el", ".emacs", null, "elisp");
        g("elixir", "Elixir", "ex exs", "", "elixir-lang/tree-sitter-elixir", "elixir");
        g("elm", "Elm", "elm", "", "elm-tooling/tree-sitter-elm", "elm");
        g("embedded-template", "Embedded template", "erb ejs", "", "tree-sitter/tree-sitter-embedded-template", "embedded-template");
        g("erlang", "Erlang", "erl hrl", "rebar.config", "WhatsApp/tree-sitter-erlang", "erlang");
        g("fennel", "Fennel", "fnl", "", null, "fennel");
        g("fish", "Fish", "fish", "", null, "fish");
        g("fortran", "Fortran", "f90 f95 f03 f08", "", null, "fortran");
        g("gitattributes", "Git attributes", "", ".gitattributes", null, "git-attributes");
        g("gitignore", "Git ignore", "", ".gitignore .dockerignore", null, "git-ignore");
        g("gleam", "Gleam", "gleam", "", "gleam-lang/tree-sitter-gleam", "gleam");
        g("glsl", "GLSL", "glsl vert frag geom comp tesc tese", "", null, "glsl");
        g("go", "Go", "go", "", "tree-sitter/tree-sitter-go", "go");
        g("go-mod", "Go module", "", "go.mod", null, "gomod");
        g("go-work", "Go workspace", "", "go.work", null, "gowork");
        g("graphql", "GraphQL", "graphql gql", "", null, "graphql");
        g("haskell", "Haskell", "hs", "", "tree-sitter/tree-sitter-haskell", "haskell");
        g("hcl", "HCL", "hcl tf tfvars", "", "MichaHoffmann/tree-sitter-hcl", "hcl");
        g("hocon", "HOCON", "hocon", "", null, "hocon");
        g("html", "HTML", "html htm xhtml", "", "tree-sitter/tree-sitter-html", "html");
        g("java", "Java", "java", "", "tree-sitter/tree-sitter-java", "java");
        g("javascript", "JavaScript", "js mjs cjs jsx", "", "tree-sitter/tree-sitter-javascript", "javascript");
        g("jq", "jq", "jq", "", null, "jq");
        g("json", "JSON", "json jsonc", ".babelrc .eslintrc", "tree-sitter/tree-sitter-json", "json");
        g("json5", "JSON5", "json5", "", null, "json5");
        g("julia", "Julia", "jl", "", "tree-sitter/tree-sitter-julia", "julia");
        g("kotlin", "Kotlin", "kt kts", "", "fwcd/tree-sitter-kotlin", "0.3.8", "kotlin", null);
        g("latex", "LaTeX", "tex sty cls ltx", "", "latex-lsp/tree-sitter-latex", "latex");
        g("lean", "Lean", "lean", "", null, "lean");
        g("llvm", "LLVM IR", "ll", "", null, "llvm");
        g("lua", "Lua", "lua", "", "Azganoth/tree-sitter-lua", "lua");
        g("make", "Makefile", "mk mak make", "Makefile makefile GNUmakefile", null, "make");
        g("markdown", "Markdown", "md markdown", "", null, "markdown");
        g("meson", "Meson", "", "meson.build meson_options.txt meson.options", null, "meson");
        g("nginx", "nginx", "", "nginx.conf", null, "nginx");
        g("nim", "Nim", "nim nims nimble", "", null, "nim");
        g("nix", "Nix", "nix", "", null, "nix");
        g("ocaml", "OCaml", "ml", "", "tree-sitter/tree-sitter-ocaml", "ocaml");
        g("org", "Org", "org", "", "milisims/tree-sitter-org", null);
        g("pascal", "Pascal", "pas dpr lpr", "", null, "pascal");
        g("perl", "Perl", "pl pm", "", null, "perl");
        g("php", "PHP", "php phtml", "", "tree-sitter/tree-sitter-php", "php");
        g("proto", "Protocol Buffers", "proto", "", null, "protobuf");
        g("python", "Python", "py pyi pyw", "", "tree-sitter/tree-sitter-python", "python");
        g("qmljs", "QML", "qml", "", null, "qml");
        g("r", "R", "r", ".Rprofile", null, "r");
        g("racket", "Racket", "rkt", "", null, "racket");
        g("rst", "reStructuredText", "rst", "", "stsewd/tree-sitter-rst", "rst");
        g("ruby", "Ruby", "rb rake gemspec ru", "Gemfile Rakefile", "tree-sitter/tree-sitter-ruby", "ruby");
        g("rust", "Rust", "rs", "", "tree-sitter/tree-sitter-rust", "rust");
        g("scala", "Scala", "scala sc sbt", "", "tree-sitter/tree-sitter-scala", "scala");
        g("scheme", "Scheme", "scm ss", "", null, "scheme");
        g("scss", "SCSS", "scss", "", null, "scss");
        g("sourcepawn", "SourcePawn", "sp", "", null, "sourcepawn");
        g("sql", "SQL", "sql", "", null, "sql");
        g("svelte", "Svelte", "svelte", "", null, "svelte");
        g("swift", "Swift", "swift", "", null, "swift");
        g("thrift", "Thrift", "thrift", "", null, "thrift");
        g("toml", "TOML", "toml", "Cargo.lock uv.lock poetry.lock", null, "toml");
        g("twig", "Twig", "twig", "", null, "twig");
        g("typescript", "TypeScript", "ts mts cts", "", "tree-sitter/tree-sitter-typescript", null, "typescript", "javascript");
        g("tsx", "TSX", "tsx", "", "tree-sitter/tree-sitter-typescript", null, "tsx", "javascript");
        g("verilog", "Verilog", "v sv svh", "", null, "verilog");
        g("vhdl", "VHDL", "vhd vhdl", "", null, "vhdl");
        g("vue", "Vue", "vue", "", null, "vue");
        g("wast", "WebAssembly script", "wast", "", null, "wast");
        g("wat", "WebAssembly text", "wat", "", null, "wat");
        g("wgsl", "WGSL", "wgsl", "", null, "wgsl");
        g("yaml", "YAML", "yaml yml", ".clang-format .clangd", null, "yaml");
        g("zig", "Zig", "zig zon", "", null, "zig");
    }

    record Built(Grammar grammar, String version, String sha256, String symbol, Set<String> platforms,
                 String source, int kept, int dropped) {
    }

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path queries = repo.resolve("syntax/resources/tree-sitter");
        Files.createDirectories(CACHE);
        Map<String, Built> built = new ConcurrentHashMap<>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> jobs = new ArrayList<>();
            for (Grammar grammar : GRAMMARS) {
                jobs.add(pool.submit(() -> {
                    try {
                        Built b = build(grammar, queries);
                        if (b != null) built.put(grammar.id(), b);
                    } catch (Exception e) {
                        System.err.println("SKIP " + grammar.id() + ": " + e);
                    }
                    return null;
                }));
            }
            for (Future<?> job : jobs) job.get();
        }
        List<Built> sorted = GRAMMARS.stream().map(g -> built.get(g.id())).filter(b -> b != null).toList();
        Files.writeString(repo.resolve("syntax/src/treesitter/GrammarCatalog.kt"), catalog(sorted));
        StringBuilder notice = new StringBuilder(
            "Highlight queries of the tree-sitter grammars, generated by tools/tree-sitter/UpdateGrammars.java.\n"
                + "Each file keeps the license of its source: the license of the grammar's repository, or MPL-2.0 for\n"
                + "Helix (https://github.com/helix-editor/helix/blob/master/LICENSE).\n\n");
        for (Built b : sorted) notice.append(b.grammar().id()).append(": ").append(b.source()).append('\n');
        Files.writeString(queries.resolve("NOTICE"), notice);
        for (Built b : sorted) {
            System.out.printf("%-18s %-10s %-22s kept %4d dropped %3d  %s%n", b.grammar().id(), b.version(),
                b.symbol(), b.kept(), b.dropped(), b.platforms());
        }
        System.out.println(sorted.size() + " grammars");
    }

    static Built build(Grammar grammar, Path queries) throws Exception {
        String artifact = "tree-sitter-" + grammar.id();
        String version = latestVersion(artifact);
        Path jar = download(MAVEN + artifact + "/" + version + "/" + artifact + "-" + version + ".jar",
            CACHE.resolve(artifact + "-" + version + ".jar"));
        String sha = sha256(jar);
        Set<String> platforms = new TreeSet<>();
        Path native_ = null;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Pattern name = Pattern.compile("lib/(.+)-" + Pattern.quote(artifact) + "\\.(so|dylib|dll)");
            for (var entry : java.util.Collections.list(zip.entries())) {
                Matcher m = name.matcher(entry.getName());
                if (!m.matches()) continue;
                platforms.add(m.group(1));
                if (m.group(1).equals("x86_64-linux-gnu")) {
                    native_ = CACHE.resolve(artifact + "-" + version + ".so");
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, native_, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        if (native_ == null) throw new IOException("no x86_64 Linux build to validate the query with");
        String symbol = symbol(native_, grammar.id());
        Language language = Language.load(SymbolLookup.libraryLookup(native_, Arena.global()), symbol);

        String source;
        String text;
        String upstream = upstreamQuery(grammar, version);
        if (upstream != null) {
            String ref = grammar.upstreamRef() != null ? grammar.upstreamRef() : upstreamRef(grammar, version);
            source = "https://github.com/" + grammar.upstream() + "/blob/" + ref + "/queries/highlights.scm";
            text = upstream;
            if (grammar.before() != null) {
                Grammar parent = GRAMMARS.stream().filter(g -> g.id().equals(grammar.before())).findFirst().orElseThrow();
                String parentText = upstreamQuery(parent, latestVersion("tree-sitter-" + parent.id()));
                // The language's own patterns come last, so that they win over the ones it extends.
                if (parentText != null) text = parentText + "\n" + text;
                source += " + " + parent.id();
            }
        } else if (grammar.helix() != null) {
            text = helix(grammar.helix());
            if (text == null) throw new IOException("no highlight query");
            source = "https://github.com/helix-editor/helix/blob/" + HELIX + "/runtime/queries/" + grammar.helix()
                + "/highlights.scm";
        } else {
            throw new IOException("no highlight query");
        }
        List<String> patterns = new ArrayList<>(patterns(text));
        // The editor, like tree-sitter's highlighter and the grammars' queries, lets the last pattern capturing a
        // node win; in Helix's queries the first one wins: their order is reversed.
        if (upstream == null) java.util.Collections.reverse(patterns);
        StringBuilder kept = new StringBuilder();
        int keptCount = 0;
        int dropped = 0;
        for (String pattern : patterns) {
            if (usable(language, pattern)) {
                kept.append(pattern).append('\n');
                keptCount++;
            } else {
                dropped++;
            }
        }
        if (keptCount == 0) throw new IOException("no pattern of the query compiles");
        // The whole query must compile too (it then does in the editor).
        language.query(kept.toString()).close();
        Path out = queries.resolve(grammar.id()).resolve("highlights.scm");
        Files.createDirectories(out.getParent());
        String order = upstream == null ? " Patterns are in reverse order (the last one capturing a node wins)." : "";
        Files.writeString(out, "; From " + source + "\n; Generated by tools/tree-sitter/UpdateGrammars.java: "
            + "patterns this grammar does not support are left out." + order + "\n\n" + kept);
        return new Built(grammar, version, sha, symbol, platforms, source, keptCount, dropped);
    }

    static boolean usable(Language language, String pattern) {
        Matcher predicate = Pattern.compile("\\(#([a-z-]+[?!])").matcher(pattern);
        while (predicate.find()) {
            if (!PREDICATES.contains(predicate.group(1))) return false;
        }
        try {
            language.query(pattern).close();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Top-level patterns of a query: a node, alternation or string, with its captures, quantifiers, predicates. */
    static List<String> patterns(String query) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        int i = 0;
        while (i < query.length()) {
            char c = query.charAt(i);
            if (c == ';') {
                int end = query.indexOf('\n', i);
                i = end < 0 ? query.length() : end;
                continue;
            }
            boolean startsPattern = depth == 0 && (c == '(' || c == '[' || c == '"');
            // At top level, a `(` right after a pattern opens its predicate: `(node) @x (#eq? @x "a")`.
            boolean predicate = depth == 0 && c == '(' && query.startsWith("(#", i);
            if (startsPattern && !predicate && !current.toString().isBlank()) {
                result.add(current.toString().strip());
                current.setLength(0);
            }
            if (c == '"') {
                int j = i + 1;
                while (j < query.length() && query.charAt(j) != '"') j += query.charAt(j) == '\\' ? 2 : 1;
                current.append(query, i, Math.min(j + 1, query.length()));
                i = j + 1;
                continue;
            }
            if (c == '(' || c == '[') depth++;
            if (c == ')' || c == ']') depth--;
            current.append(c);
            i++;
        }
        if (!current.toString().isBlank()) result.add(current.toString().strip());
        return result;
    }

    static String upstreamRef(Grammar grammar, String version) {
        String v = version.replaceAll("a$", "");
        if (v.equals("main-") || v.equals("master-")) return v.substring(0, v.length() - 1);
        return v;
    }

    static String upstreamQuery(Grammar grammar, String version) throws Exception {
        if (grammar.upstream() == null) return null;
        String ref = grammar.upstreamRef() != null ? grammar.upstreamRef() : upstreamRef(grammar, version);
        for (String tag : grammar.upstreamRef() != null ? List.of(ref) : List.of("v" + ref, ref)) {
            String text = fetch("https://raw.githubusercontent.com/" + grammar.upstream() + "/" + tag
                + "/queries/highlights.scm");
            if (text != null) return text;
        }
        return null;
    }

    static final Map<String, String> HELIX_CACHE = new ConcurrentHashMap<>();

    /** Helix's query of [name], with the queries it inherits (`; inherits: a,b`) in their place. */
    static String helix(String name) throws Exception {
        String cached = HELIX_CACHE.get(name);
        if (cached != null) return cached;
        String text = fetch("https://raw.githubusercontent.com/helix-editor/helix/" + HELIX + "/runtime/queries/"
            + name + "/highlights.scm");
        if (text == null) return null;
        Matcher inherits = Pattern.compile("(?m)^;+ *inherits *:? *([a-z0-9_,-]+) *$").matcher(text);
        StringBuilder out = new StringBuilder();
        while (inherits.find()) {
            StringBuilder parents = new StringBuilder();
            for (String parent : inherits.group(1).split(",")) {
                String p = helix(parent.trim());
                if (p != null) parents.append(p).append('\n');
            }
            inherits.appendReplacement(out, Matcher.quoteReplacement(parents.toString()));
        }
        inherits.appendTail(out);
        HELIX_CACHE.put(name, out.toString());
        return out.toString();
    }

    static final Map<String, String> VERSIONS = new ConcurrentHashMap<>();

    static String latestVersion(String artifact) throws Exception {
        String cached = VERSIONS.get(artifact);
        if (cached != null) return cached;
        String metadata = fetch(MAVEN + artifact + "/maven-metadata.xml");
        if (metadata == null) throw new IOException("not on Maven Central");
        Matcher m = Pattern.compile("<release>([^<]+)</release>").matcher(metadata);
        if (!m.find()) m = Pattern.compile("<latest>([^<]+)</latest>").matcher(metadata);
        if (!m.find(0)) throw new IOException("no version");
        VERSIONS.put(artifact, m.group(1));
        return m.group(1);
    }

    /** The entry point of the grammar: `tree_sitter_<name>` exported by the library. */
    static String symbol(Path library, String id) throws Exception {
        Process nm = new ProcessBuilder("nm", "-D", "--defined-only", library.toString()).start();
        String out = new String(nm.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        nm.waitFor();
        List<String> symbols = new ArrayList<>();
        Matcher m = Pattern.compile("(?m) T (tree_sitter_[a-z0-9_]+)$").matcher(out);
        while (m.find()) {
            if (!m.group(1).contains("_external_scanner_")) symbols.add(m.group(1));
        }
        String expected = "tree_sitter_" + id.replace('-', '_');
        if (symbols.contains(expected)) return expected;
        if (symbols.size() == 1) return symbols.get(0);
        throw new IOException("entry point not found among " + symbols);
    }

    static String fetch(String url) throws Exception {
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) return response.body();
            if (response.statusCode() == 404) return null;
            if (attempt == 3) throw new IOException(url + ": HTTP " + response.statusCode());
            Thread.sleep(1000L << attempt);
        }
    }

    static Path download(String url, Path target) throws Exception {
        if (Files.isRegularFile(target)) return target;
        Path temp = Files.createTempFile(CACHE, "download", ".tmp");
        HttpResponse<Path> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
            HttpResponse.BodyHandlers.ofFile(temp));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(temp);
            throw new IOException(url + ": HTTP " + response.statusCode());
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    static final Set<String> ALL_PLATFORMS = new TreeSet<>(Set.of(
        "aarch64-linux-gnu", "aarch64-macos", "x86_64-linux-gnu", "x86_64-macos", "x86_64-windows"));

    static String catalog(List<Built> grammars) {
        StringBuilder out = new StringBuilder();
        out.append("""
            // Versions repeat ("main-a": the grammar's main branch, rebuilt by bonede).
            @file:Suppress("StringLiteralDuplication")

            package org.example.kotlineditor.syntax.treesitter

            // Generated by tools/tree-sitter/UpdateGrammars.java: do not edit, run tools/tree-sitter/update-grammars.

            private val ALL_PLATFORMS =
                setOf(
                    "aarch64-linux-gnu",
                    "aarch64-macos",
                    "x86_64-linux-gnu",
                    "x86_64-macos",
                    "x86_64-windows",
                )

            /** The grammars the editor highlights with, downloaded on demand (see [GrammarStore]). */
            internal val GRAMMAR_CATALOG: List<GrammarSpec> =
                listOf(
            """);
        for (Built b : grammars) {
            Grammar g = b.grammar();
            out.append("        GrammarSpec(\n");
            out.append("            id = \"").append(g.id()).append("\",\n");
            out.append("            name = \"").append(g.name()).append("\",\n");
            out.append("            version = \"").append(b.version()).append("\",\n");
            out.append("            sha256 = \"").append(b.sha256()).append("\",\n");
            out.append("            symbol = \"").append(b.symbol()).append("\",\n");
            out.append(set("platforms", b.platforms().equals(ALL_PLATFORMS) ? null : b.platforms()));
            out.append(set("extensions", words(g.extensions())));
            out.append(set("fileNames", words(g.fileNames())));
            out.append("        ),\n");
        }
        out.append("    )\n");
        return out.toString();
    }

    // `name = setOf(...),` on one line when it fits, else one value per line; null for ALL_PLATFORMS.
    static String set(String name, java.util.Collection<String> values) {
        String prefix = "            " + name + " = ";
        if (values == null) return prefix + "ALL_PLATFORMS,\n";
        if (values.isEmpty()) return prefix + "emptySet(),\n";
        String line = prefix + "setOf(" + quoted(values) + "),";
        if (line.length() <= 120) return line + "\n";
        StringBuilder out = new StringBuilder(prefix).append("\n                setOf(\n");
        for (String value : values) out.append("                    \"").append(value).append("\",\n");
        return out.append("                ),\n").toString();
    }

    static List<String> words(String text) {
        return text.isBlank() ? List.of() : List.of(text.trim().split(" +"));
    }

    static String quoted(java.util.Collection<String> values) {
        return String.join(", ", values.stream().map(v -> "\"" + v + "\"").toList());
    }
}
