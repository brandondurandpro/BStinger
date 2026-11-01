# Tree-sitter grammars

The editor colors files with [tree-sitter](https://tree-sitter.github.io/), through its official Java binding
([jtreesitter](https://github.com/tree-sitter/java-tree-sitter), FFM). Only the tree-sitter runtime ships with the IDE
(the `io.github.bonede:tree-sitter` jar carries it for every platform). The grammars are downloaded by the IDE, on the
machine it runs on, the first time a file of their language opens.

## How it works

- `shared/src/syntax/treesitter/GrammarCatalog.kt` (generated) lists every grammar: the prebuilt jar
  `io.github.bonede:tree-sitter-<id>:<version>` from Maven Central
  ([bonede/tree-sitter-ng](https://github.com/bonede/tree-sitter-ng)), its SHA-256, its platforms, its entry point,
  and the extensions and file names it colors.
- `GrammarStore` downloads the jar, checks its SHA-256, keeps the library of the current platform in
  `~/.kotlin-editor/tree-sitter`, and retries a failed download with a growing delay. Until the grammar is there (or
  if it never can be: offline, unsupported platform, file over 16 MB), the line lexer colors the file.
- `shared/resources/tree-sitter/<id>/highlights.scm` (generated) are the highlight queries, from the grammar's own
  repository at the built version when it has one, else from [Helix](https://github.com/helix-editor/helix). The
  patterns the grammar rejects, and those using a predicate the editor does not evaluate, are left out. As in
  tree-sitter's highlighter, the last pattern capturing a node wins: Helix's queries, where the first one wins, are
  written in reverse order. `NOTICE` lists the source of each query.
- `SyntaxSession` parses a document again after each change, incrementally and off the UI thread, and colors the
  lines around the visible ones only. `CaptureStyles` maps capture names (`keyword`, `function.method`...) to colors.

## Updating the grammars

```sh
tools/tree-sitter/update-grammars
```

It downloads every grammar listed in `UpdateGrammars.java` (latest version on Maven Central), records its SHA-256,
fetches and checks its query, and rewrites the catalog and the queries. It needs a JDK 23+, `curl`, `unzip` and `nm`,
on Linux x86_64 (the queries are checked against the grammars' Linux build). To add a language, add a line to the
list in `UpdateGrammars.java` and run it.

The tests download `tree-sitter-kotlin` and `tree-sitter-json` from their test classpath: keep their versions in
`libs.versions.toml` equal to the catalog's (`GrammarCatalogTest` checks it).
