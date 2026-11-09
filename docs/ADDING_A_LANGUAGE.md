# Adding a language

A language is declared once, in `protocol/src/model/LanguageSupport.kt`, then completed in the modules that know its
syntax and its language server. Tests fail when a step is forgotten.

## 1. Declare it: `LanguageSupport`

Add an `object` to the sealed class `LanguageSupport` and list it in `LanguageSupport.all` (a test fails if a
language is declared but not listed). Each language says:

| Facet | Field | What it drives |
| --- | --- | --- |
| Name | `name` | Names for people |
| Files | `extensions` | Lowercase, without the dot; an extension belongs to one language |
| Icon | `icon: LanguageIcon` | Label, color and shape (`IconShape`) of the icon in the tree, tabs and popups |
| Language server | `lsp: LspSupport?` | `null` when the IDE has no server for it (Kotlin, Markdown…) |

`LspSupport` gives the language identifier told to the servers in `didOpen` (`typescriptreact` for a `.tsx`), the
`group` (the language of the server that serves several identifiers), the `companions` (Tailwind CSS, Vue…), the
`rootMarker` (`composer.json`: the server runs in the closest directory that has it), and whether the files get code
insight (`insight`: usage counts, hints, problems) or only completion and hover.

A new shape of icon is a value of `IconShape` and a branch of `drawBadgeShape` (`ui/kit/src/FileIconDrawing.kt`).

## 2. Its syntax colors: the tree-sitter grammar

Add its grammar to `syntax/src/treesitter/GrammarCatalog.kt` (and its `highlights.scm` under
`syntax/resources/tree-sitter/<id>/`). A language with no grammar is colored by the line lexer and must be listed in
`lexerOnly` of `LanguageSupportSyntaxTest`.

## 3. Its language server

When the language has a server of its own, add its candidate to the registry of `lsp/src/provision`
(`LanguageServerCandidates.kt`, `WebServers.kt` for the web): command, runtime requirements, download, options. A
language only served by companion servers is listed in `companionsOnly` of `LanguageSupportServersTest`.

A companion server is itself a language (`Languages.TAILWIND`, `Languages.VUE_SERVER`): give it a name in
`Languages.names`, a candidate, and name it in the `companions` of the languages it serves.

## 4. Tests

- `LanguageSupportTest` (protocol), `LanguageSupportSyntaxTest` (syntax) and `LanguageSupportServersTest` (lsp) check
  the declaration, the grammar and the server of every language.
- A language with a server needs, like any feature, e2e tests (see `AGENTS.md`): completion, hover, definitions, with
  the fake server of `testkit`, and an integration test with the real server when it can be downloaded in CI (see
  `lsp/test/provision/WebFeaturesIntegrationTest.kt`).
